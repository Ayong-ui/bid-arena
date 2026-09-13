package com.bidarena.agentaccess.persistence;

import com.bidarena.agentaccess.domain.AgentScope;
import com.bidarena.agentaccess.domain.AgentScopes;
import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.shared.BizException;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * 竞拍 Agent Token 的读写。
 *
 * <p>这里有一条不可动摇的约定：<b>写进去的永远是摘要，读出来的也永远不含明文</b>。
 * 因此本类没有"按明文查"的方法——唯一能找到 Token 的入口是 {@link #findByHash}，
 * 调用方必须先自己把明文摘要化。这个设计让"明文入库"在类型层面就不可能发生。
 *
 * <p>鉴权路径上的读是"先查摘要再判断状态"，不加锁：Token 的吊销是低频运营动作，
 * 而每一次出价都去锁 agent_tokens 行只会制造无谓的争用。代价是"吊销后最多一个请求
 * 的空窗期"，当用户在发起吊销前已经带上了 Token 时，本来就是无法避免的。
 */
public class AgentTokenRepository {

    private final DataSource dataSource;

    public AgentTokenRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 写入一枚 Token（摘要）及其拍卖范围。
     *
     * <p>两条 INSERT 必须在同一事务里：若只写进了主记录而范围写入失败，
     * 就会留下一枚"谁能用都不知道"的 Token，而它已经可以被认证通过——
     * 那是一个真实的越权窗口（范围为空 = 默认拒绝，虽不致越权，但会得到一个
     * 无法使用却已生效的凭证，运维只能靠删库收拾）。
     */
    public void insert(String tokenId, String name, String tokenHash, String agentUserId,
            Set<AgentScope> scopes, Set<String> auctionIds, int rateLimitPerMinute, Instant expiresAt) {
        Db.tx(dataSource, conn -> {
            Db.update(conn,
                    "INSERT INTO agent_tokens (token_id, name, token_hash, agent_user_id, scopes, "
                            + "rate_limit_per_minute, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    tokenId, name, tokenHash, agentUserId, wireScopes(scopes), rateLimitPerMinute,
                    Db.ts(expiresAt));
            for (String auctionId : auctionIds) {
                Db.update(conn,
                        "INSERT INTO agent_token_auctions (token_id, auction_id) VALUES (?, ?)",
                        tokenId, auctionId);
            }
            return null;
        });
    }

    /** 按摘要查 Token；返回 null 表示这枚 Token 从未签发过。 */
    public AgentToken findByHash(String tokenHash) {
        return Db.read(dataSource, conn -> load(conn, "token_hash = ?", tokenHash));
    }

    /** 按对外标识查 Token（吊销、审计用）。 */
    public AgentToken findByTokenId(String tokenId) {
        return Db.read(dataSource, conn -> load(conn, "token_id = ?", tokenId));
    }

    /**
     * 吊销一枚 Token。<b>幂等</b>：`COALESCE` 保证第二次吊销不会覆盖首次的时间戳，
     * 也保证它仍然返回 true（记录存在）。返回 false 只表示"没有这枚 Token"。
     *
     * <p>不物理删除：吊销状态本身是审计事实，"这枚 Token 曾经存在过、在某刻被停用"
     * 比"查无此 Token"更能解释历史。
     */
    public boolean revoke(String tokenId, Instant now) {
        Integer affected = Db.read(dataSource, conn -> Db.update(conn,
                "UPDATE agent_tokens SET revoked_at = COALESCE(revoked_at, ?) WHERE token_id = ?",
                Db.ts(now), tokenId));
        return affected != null && affected > 0;
    }

    // ---------------------------------------------------------------- 列表（管理视图）

    /**
     * 一枚 Token 的管理摘要。
     *
     * <p>为什么不复用 {@link AgentToken}：那个类型是<b>授权判定</b>用的，缺少
     * {@code createdAt}（审计需要）与"已吊销时刻"的可空语义；把管理字段塞进它，
     * 会让鉴权路径上的对象携带一批与判定无关的字段，日后很难说清哪些字段被谁读过。
     */
    public record TokenSummaryRow(String tokenId, String name, String agentUserId, Set<String> scopes,
            Set<String> auctionIds, int rateLimitPerMinute, Instant expiresAt, Instant revokedAt,
            Instant createdAt) {}

    /** 某用户名下的 Token，按签发时间倒序（新的在前）。 */
    public List<TokenSummaryRow> pageByAgentUser(String agentUserId, int limit, int offset) {
        return summaries("agent_user_id = ?", List.of(agentUserId), limit, offset);
    }

    public long countByAgentUser(String agentUserId) {
        Long total = Db.read(dataSource, conn -> Db.queryOne(conn,
                "SELECT COUNT(*) FROM agent_tokens WHERE agent_user_id = ?", rs -> rs.getLong(1), agentUserId));
        return total == null ? 0 : total;
    }

    /** 全部 Token（管理员视图），按签发时间倒序。 */
    public List<TokenSummaryRow> pageAll(int limit, int offset) {
        return summaries("", List.of(), limit, offset);
    }

    public long countAll() {
        Long total = Db.read(dataSource, conn -> Db.queryOne(conn,
                "SELECT COUNT(*) FROM agent_tokens", rs -> rs.getLong(1)));
        return total == null ? 0 : total;
    }

    /**
     * 一次查一页 Token，再用一条 {@code IN} 批量补齐拍卖范围。
     *
     * <p>刻意不做"每枚 Token 各查一次范围"：列表页最多 100 行，那会是 100 次往返。
     * 两条查询就能拿全，且不改变任何语义。
     */
    private List<TokenSummaryRow> summaries(String where, List<Object> whereArgs, int limit, int offset) {
        return Db.read(dataSource, conn -> {
            List<Object> params = new ArrayList<>(whereArgs);
            params.add(limit);
            params.add(offset);
            String sql = "SELECT token_id, name, agent_user_id, scopes, rate_limit_per_minute, "
                    + "expires_at, revoked_at, created_at FROM agent_tokens "
                    + (where.isEmpty() ? "" : "WHERE " + where + " ")
                    + "ORDER BY id DESC LIMIT ? OFFSET ?";
            List<Row> rows = Db.queryList(conn, sql, AgentTokenRepository::mapRow, params.toArray());
            if (rows.isEmpty()) {
                return List.of();
            }
            List<String> tokenIds = rows.stream().map(Row::tokenId).toList();
            Map<String, Set<String>> auctionIdsByToken = new HashMap<>();
            Db.queryList(conn,
                    "SELECT token_id, auction_id FROM agent_token_auctions WHERE token_id IN ("
                            + Db.placeholders(tokenIds.size()) + ") ORDER BY auction_id",
                    rs -> Map.entry(rs.getString(1), rs.getString(2)), tokenIds.toArray())
                    .forEach(entry -> auctionIdsByToken
                            .computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>())
                            .add(entry.getValue()));
            List<TokenSummaryRow> summaries = new ArrayList<>(rows.size());
            for (Row row : rows) {
                summaries.add(new TokenSummaryRow(row.tokenId(), row.name(), row.agentUserId(),
                        scopeWires(parseScopes(row.scopes())),
                        Set.copyOf(auctionIdsByToken.getOrDefault(row.tokenId(), Set.of())),
                        row.rateLimitPerMinute(), row.expiresAt(), row.revokedAt(), row.createdAt()));
            }
            return summaries;
        });
    }

    // ---------------------------------------------------------------- 映射

    private static AgentToken load(java.sql.Connection conn, String where, Object arg) throws SQLException {
        Row row = Db.queryOne(conn,
                "SELECT token_id, name, agent_user_id, scopes, rate_limit_per_minute, expires_at, revoked_at, created_at "
                        + "FROM agent_tokens WHERE " + where,
                AgentTokenRepository::mapRow, arg);
        if (row == null) {
            return null;
        }
        Set<String> auctionIds = new HashSet<>(Db.queryList(conn,
                "SELECT auction_id FROM agent_token_auctions WHERE token_id = ?",
                rs -> rs.getString(1), row.tokenId()));
        return new AgentToken(row.tokenId(), row.name(), row.agentUserId(), parseScopes(row.scopes()),
                auctionIds, row.rateLimitPerMinute(), row.expiresAt(), row.revokedAt());
    }

    private static Row mapRow(ResultSet rs) throws SQLException {
        return new Row(rs.getString("token_id"), rs.getString("name"), rs.getString("agent_user_id"),
                rs.getString("scopes"), rs.getInt("rate_limit_per_minute"),
                Db.instant(rs, "expires_at"), Db.instant(rs, "revoked_at"), Db.instant(rs, "created_at"));
    }

    private record Row(String tokenId, String name, String agentUserId, String scopes,
            int rateLimitPerMinute, Instant expiresAt, Instant revokedAt, Instant createdAt) {}

    static String wireScopes(Set<AgentScope> scopes) {
        return AgentScopes.wire(scopes);
    }

    /** 权限集合 → 有序的契约字面量集合，供管理视图输出。 */
    private static Set<String> scopeWires(Set<AgentScope> scopes) {
        return scopes.stream().map(AgentScope::wire).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * 解析库里的权限列。库里的值无法解析属于<b>数据损坏</b>，不是调用方的参数错误，
     * 因此这里把 {@link AgentScope#parse} 的 400 翻成 500：把它当 400 会让客户端
     * 收到"你的 scope 非法"，而真正该看这条日志的是运维。
     */
    private static Set<AgentScope> parseScopes(String csv) {
        try {
            return AgentScopes.parseCsv(csv);
        } catch (BizException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "数据库中保存了无法解析的 Agent 权限值", Map.of("raw", String.valueOf(csv)));
        }
    }
}
