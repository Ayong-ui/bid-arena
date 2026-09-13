package com.bidarena.agentaccess.application;

import com.bidarena.agentaccess.domain.AgentScope;
import com.bidarena.agentaccess.domain.AgentScopes;
import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.agentaccess.persistence.AgentTokenRepository;
import com.bidarena.agentaccess.persistence.AgentTokenRepository.TokenSummaryRow;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.shared.PageQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 竞拍 Agent 凭证的签发、认证、授权与吊销。
 *
 * <h2>明文只出现一次</h2>
 * {@link #issue} 生成 32 字节随机值并返回明文；库里只写它的 SHA-256 摘要。
 * 这意味着"取回已签发的 Token"在系统里不存在——丢了只能重新签发。
 * 这不是缺陷，而是这类凭证的固有代价：如果服务端能还原明文，那么拖库就能拿到
 * 所有可用 Token，摘要存储也就白做了。
 *
 * <h2>三类失败，三个码</h2>
 * <ul>
 *   <li><b>401</b>：Token 不认识、已过期、已吊销。不区分具体原因——区分了就等于告诉
 *       持有者"这枚 Token 是真的，只是过期了"，那是可用于试探的信息。</li>
 *   <li><b>403</b>：Token 有效，但越权（缺少权限，或拍卖不在范围内）。</li>
 *   <li><b>429</b>：Token 有效且在范围内，但频率超限。</li>
 * </ul>
 *
 * <h2>为什么用 {@link Clock} 而不是 {@code Instant.now()}</h2>
 * 过期与限流都依赖"现在几点"。把时钟注入进来，单元测试才能用固定时刻验证边界
 * （例如"恰好等于 expiresAt 算过期"），而不是靠 {@code Thread.sleep} 猜时间。
 * 注意：这只影响凭证自身的时间判断；拍卖截止时间仍由数据库时间决定（D-5）。
 */
public class AgentTokenService {

    /** 32 字节 = 256 位熵；base64url 后 43 个字符，足够抵御暴力猜测。 */
    private static final int TOKEN_BYTES = 32;

    private static final int NAME_MAX = 80;
    private static final int SCOPE_COLUMN_MAX = 128;
    private static final int DEFAULT_RATE_LIMIT = 60;
    private static final int RATE_LIMIT_MAX = 6_000;

    private final AgentTokenRepository tokens;
    private final AgentRateLimiter rateLimiter;
    private final AuctionScopeLookup auctionScopes;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AgentTokenService(AgentTokenRepository tokens, AgentRateLimiter rateLimiter,
            AuctionScopeLookup auctionScopes) {
        this(tokens, rateLimiter, auctionScopes, Clock.systemUTC());
    }

    public AgentTokenService(AgentTokenRepository tokens, AgentRateLimiter rateLimiter,
            AuctionScopeLookup auctionScopes, Clock clock) {
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.auctionScopes = auctionScopes;
        this.clock = clock;
    }

    /** 签发请求，字段与契约 {@code CreateAgentTokenRequest} 一致。 */
    public record IssueCommand(String name, String agentUserId, List<String> auctionIds,
            List<String> scopes, Instant expiresAt, Integer rateLimitPerMinute) {}

    /** 签发结果：明文 Token 只在这里返回一次。 */
    public record Issued(String token, String tokenId, Instant expiresAt) {}

    /**
     * 签发一枚 Token。
     *
     * <p>过期时间必须严格晚于当前时刻：签发一枚"出生即过期"的 Token 是一个参数错误，
     * 若默默接受，调用方要等到第一次调用收到 401 才发现，而那时它已经把这枚 Token
     * 交给了一个 Agent。
     */
    public Issued issue(IssueCommand command) {
        if (command == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        String name = requireText(command.name(), "name", NAME_MAX);
        String agentUserId = requireText(command.agentUserId(), "agentUserId", 64);

        Set<AgentScope> scopes = parseScopes(command.scopes());
        if (scopes.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "scopes 不能为空");
        }
        if (AgentScopes.wire(scopes).length() > SCOPE_COLUMN_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "scopes 组合过长", Map.of("max", SCOPE_COLUMN_MAX));
        }

        Instant now = clock.instant();
        if (command.expiresAt() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "expiresAt 不能为空");
        }
        if (!command.expiresAt().isAfter(now)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "expiresAt 必须晚于当前时间",
                    Map.of("expiresAt", command.expiresAt().toString(), "serverTime", now.toString()));
        }

        int rateLimit = command.rateLimitPerMinute() == null ? DEFAULT_RATE_LIMIT : command.rateLimitPerMinute();
        if (rateLimit < 1 || rateLimit > RATE_LIMIT_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "rateLimitPerMinute 必须在 1.." + RATE_LIMIT_MAX + " 之间",
                    Map.of("rateLimitPerMinute", rateLimit));
        }

        Set<String> auctionIds = parseAuctionIds(command.auctionIds());
        requireKnownAuctions(auctionIds);

        String tokenId = "agt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String plaintext = newToken();
        tokens.insert(tokenId, name, sha256Hex(plaintext), agentUserId, scopes, auctionIds, rateLimit,
                command.expiresAt());

        // 明文与摘要都在这里产生、也在这里分手：调用方拿到明文，库里只有摘要。
        return new Issued(plaintext, tokenId, command.expiresAt());
    }

    /**
     * 用明文 Token 认证。任何无效原因都收敛成 401。
     *
     * <p>先摘要再按摘要查：整条路径上没有"按明文比较"的 SQL，
     * 因此即便某段日志打印了 SQL，也不会带出可用凭证。
     */
    public AgentToken authenticate(String presented) {
        if (presented == null || presented.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "缺少 Agent Token");
        }
        AgentToken token = tokens.findByHash(sha256Hex(presented.trim()));
        if (token == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "Agent Token 无效");
        }
        if (!token.activeAt(clock.instant())) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "Agent Token 已过期或被吊销");
        }
        return token;
    }

    /** 消耗一次频率配额；超限抛 429。 */
    public void checkRateLimit(AgentToken token) {
        if (!rateLimiter.tryAcquire(token.tokenId(), token.rateLimitPerMinute(), clock.instant())) {
            throw new BizException(ErrorCode.RATE_LIMITED, "请求过于频繁，已触发 Token 频率上限",
                    Map.of("limitPerMinute", token.rateLimitPerMinute()));
        }
    }

    /**
     * 授权：这枚 Token 现在能不能对这场拍卖做这个动作？
     *
     * <p>顺序是先"对不对"（权限与范围，403）再"还能不能用"（过期/吊销，401）。
     * 反过来的话，一枚已过期的越权 Token 会收到 401 而不是 403，掩盖了它本来就无权访问
     * 这场拍卖这一事实。当然，认证阶段已经把过期挡掉了；这里再判一次是为了让本方法
     * 在别处被单独调用时也自洽。
     */
    public void authorize(AgentToken token, String auctionId, AgentScope required) {
        if (!token.activeAt(clock.instant())) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "Agent Token 已过期或被吊销");
        }
        if (!token.allows(required)) {
            throw new BizException(ErrorCode.FORBIDDEN, "Agent Token 不具备该操作的权限",
                    Map.of("required", required.wire(), "granted", granted(token)));
        }
        if (!token.covers(auctionId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "Agent Token 未被授权访问该拍卖",
                    Map.of("auctionId", auctionId, "scope", "auctionIds"));
        }
    }

    /**
     * 吊销。返回吊销后的 Token 视图；不存在则 404。
     *
     * <p>已经吊销过的 Token 再次吊销返回 200 而不是报错：吊销是一个"让状态变成已吊销"的
     * 幂等指令，运维重试（或两个管理员同时点）不该看到失败。首次吊销的时间戳被保留
     * （见 {@link AgentTokenRepository#revoke}），因此审计上"何时被停用"不会漂移。
     */
    public AgentToken revoke(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "tokenId 不能为空");
        }
        if (!tokens.revoke(tokenId.trim(), clock.instant())) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent Token 不存在", Map.of("tokenId", tokenId));
        }
        return tokens.findByTokenId(tokenId.trim());
    }

    // ---------------------------------------------------------------- 自助授权（D-34）

    /**
     * 为<b>本人</b>签发一枚 Token。
     *
     * <p>把 {@code agentUserId} 强制改写成调用方本人：自助入口在类型上就表达不了
     * "替别人签发"，因此不需要在每个控制器里记得校验归属——漏一处就是一个真实越权。
     * 其余校验（权限非空、过期必须晚于现在、范围必须存在）与管理员签发完全一致。
     */
    public Issued issueForSelf(String userId, IssueCommand command) {
        IssueCommand safe = new IssueCommand(command.name(), userId, command.auctionIds(),
                command.scopes(), command.expiresAt(), command.rateLimitPerMinute());
        return issue(safe);
    }

    /**
     * 本人吊销自己的 Token。
     *
     * <p>不属于本人时返回 404 而不是 403：404 与"这枚 tokenId 不存在"无法区分，
     * 调用方因此探不出别人的 tokenId 是否存在（tokenId 虽不是秘密，但没必要的暴露就不给）。
     */
    public AgentToken revokeForAgentUser(String tokenId, String agentUserId) {
        AgentToken token = tokenId == null ? null : tokens.findByTokenId(tokenId.trim());
        if (token == null || !token.agentUserId().equals(agentUserId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent Token 不存在",
                    Map.of("tokenId", String.valueOf(tokenId)));
        }
        return revoke(token.tokenId());
    }

    /** 本人名下的授权列表（新→旧）。 */
    public PageQuery.Page<AgentTokenViews.AgentTokenSummary> listForAgentUser(String agentUserId, PageQuery page) {
        List<TokenSummaryRow> rows = tokens.pageByAgentUser(agentUserId, page.limit(), page.offset());
        long total = tokens.countByAgentUser(agentUserId);
        return summarise(rows, total, page);
    }

    /** 全部授权（管理员总览）。 */
    public PageQuery.Page<AgentTokenViews.AgentTokenSummary> listAll(PageQuery page) {
        List<TokenSummaryRow> rows = tokens.pageAll(page.limit(), page.offset());
        long total = tokens.countAll();
        return summarise(rows, total, page);
    }

    private PageQuery.Page<AgentTokenViews.AgentTokenSummary> summarise(
            List<TokenSummaryRow> rows, long total, PageQuery page) {
        Instant now = clock.instant();
        return new PageQuery.Page<>(
                rows.stream().map(row -> AgentTokenViews.AgentTokenSummary.of(row, now)).toList(),
                page.page(), page.size(), total);
    }

    // ---------------------------------------------------------------- 内部

    private static String granted(AgentToken token) {
        return token.scopes().stream().map(AgentScope::wire).sorted().collect(Collectors.joining(","));
    }

    /**
     * 授权范围里的每一场拍卖都必须存在。
     *
     * <p>空集合直接跳过查询：{@code auctionIds} 缺省是合法的（等于默认拒绝，D-29），
     * 不该为它多打一次数据库。
     */
    private void requireKnownAuctions(Set<String> auctionIds) {
        if (auctionIds.isEmpty()) {
            return;
        }
        Set<String> unknown = new LinkedHashSet<>(auctionIds);
        unknown.removeAll(auctionScopes.existing(auctionIds));
        if (!unknown.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "auctionIds 中存在不存在的拍卖",
                    Map.of("unknown", List.copyOf(unknown)));
        }
    }

    private static String requireText(String raw, String field, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 不能为空");
        }
        if (value.length() > maxLength) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 过长",
                    Map.of("maxLength", maxLength, "actual", value.length()));
        }
        return value;
    }

    private static Set<AgentScope> parseScopes(List<String> raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "scopes 不能为空");
        }
        Set<AgentScope> scopes = EnumSet.noneOf(AgentScope.class);
        for (String value : raw) {
            scopes.add(AgentScope.parse(value));
        }
        return scopes;
    }

    /**
     * 解析拍卖范围。去重并保序，空项按"没写"处理。
     *
     * <p>不做"省略即全部"的宽容：见 {@link AgentToken} 的类注释（D-29 默认拒绝）。
     */
    private static Set<String> parseAuctionIds(List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            ids.add(value.trim());
        }
        return ids;
    }

    /**
     * 生成新 Token。
     *
     * <p>用服务实例持有的那个 {@link SecureRandom}，而不是每次 {@code new}：
     * {@code SecureRandom} 是有状态的，短时间反复构造有退化到弱种子的风险。
     */
    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 十六进制摘要。
     *
     * <p>不加盐、不做慢哈希：Token 本身是 256 位随机值，不存在"字典口令"式攻击，
     * 慢哈希只会让每一次 Agent 请求都付出额外 CPU。这与用户口令必须用 BCrypt 是两回事。
     */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走到这里说明运行环境已损坏。
            throw new BizException(ErrorCode.INTERNAL_ERROR, "运行环境缺少 SHA-256");
        }
    }
}
