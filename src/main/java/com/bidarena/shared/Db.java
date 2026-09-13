package com.bidarena.shared;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 极薄的 JDBC 辅助。
 *
 * <p>刻意不引入 ORM 或 SQL 模板库：本项目的正确性取决于**精确的锁顺序与条件更新**
 * （见 {@code DECISIONS.md} D-1、D-4），每条语句在做什么必须能一眼看清。
 * 一个会替你决定何时发查询、如何加锁的框架在这里是负债而不是资产。
 *
 * <p>本类只做三件事：事务边界、参数绑定、异常翻译。
 *
 * <p><b>异常翻译只发生在事务边界</b>（也即 {@link #tx} / {@link #read}），
 * 下层辅助方法一律原样抛出 {@link SQLException}。这一点很重要：重试决策依赖原始错误码
 * （1213 死锁 / 1205 锁等待超时），如果下层提前把异常翻译成业务异常，事务边界就再也
 * 判断不出"这次失败值不值得重试"。
 */
public final class Db {

    private Db() {}

    private static final int ER_LOCK_DEADLOCK = 1213;
    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    private static final int MAX_TX_ATTEMPTS = 3;

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface TxWork<T> {
        T run(Connection conn) throws SQLException;
    }

    /**
     * 在一个事务里执行 {@code work}。
     *
     * <p><b>只有死锁与锁等待超时会重试</b>——它们是"这次运气不好"，换个顺序再来一次就可能成功。
     * 业务异常绝不重试：被拒绝就是被拒绝，重试只会让同一个请求在数据库上做无用功，
     * 并把本来可解释的拒绝变成随机结果。
     */
    public static <T> T tx(DataSource ds, TxWork<T> work) {
        SQLException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_TX_ATTEMPTS; attempt++) {
            try (Connection conn = ds.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    T result = work.run(conn);
                    conn.commit();
                    return result;
                } catch (RuntimeException | Error e) {
                    rollbackQuietly(conn);
                    throw e;
                } catch (SQLException e) {
                    rollbackQuietly(conn);
                    if (!isRetryable(e)) {
                        throw translate(e);
                    }
                    lastFailure = e;
                    backoff(attempt);
                }
            } catch (SQLException e) {
                // 连获取连接都失败，不属于"事务内部失败"，重试没有意义
                throw translate(e);
            }
        }

        throw new BizException(
                ErrorCode.CONFLICT,
                "并发冲突重试 " + MAX_TX_ATTEMPTS + " 次仍未成功",
                Map.of("lastError", String.valueOf(lastFailure == null ? null : lastFailure.getMessage())));
    }

    /**
     * 只读查询：不开事务，但仍然统一翻译异常。
     *
     * <p>用于拍卖快照、流水分页这类不参与并发判定的读路径。
     * 注意：涉及"读到的值将被用来做决定"的场景必须走 {@link #tx} 并加锁，
     * 否则就是原文明确否定的"先查询再判断"。
     */
    public static <T> T read(DataSource ds, TxWork<T> work) {
        try (Connection conn = ds.getConnection()) {
            return work.run(conn);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /**
     * 取数据库当前时间。
     *
     * <p>所有时间比较必须用这个值，不能用 {@code Instant.now()}：拍卖的截止与延时判定
     * 一旦混入应用机器的时钟，多实例部署或容器时钟漂移时，两个实例会对"是否还能出价"
     * 给出不同答案（见 {@code DECISIONS.md} D-5）。
     */
    public static Instant now(Connection conn) throws SQLException {
        return queryOne(conn, "SELECT NOW(6)", rs -> rs.getTimestamp(1).toInstant());
    }

    public static <T> T queryOne(Connection conn, String sql, RowMapper<T> mapper, Object... args)
            throws SQLException {
        List<T> rows = queryList(conn, sql, mapper, args);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            throw new IllegalStateException("期望至多一行，实际返回 " + rows.size() + " 行: " + sql);
        }
        return rows.get(0);
    }

    public static <T> List<T> queryList(Connection conn, String sql, RowMapper<T> mapper, Object... args)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        }
    }

    public static int update(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    /** 需要回填自增主键的插入。 */
    public static long insertReturningKey(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    /** 拼接 IN 列表的占位符。 */
    public static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public static boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == ER_DUP_ENTRY;
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Instant instantValue) {
                ps.setTimestamp(i + 1, Timestamp.from(instantValue));
            } else {
                ps.setObject(i + 1, arg);
            }
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败没有补救手段，且原始异常更有诊断价值，不能让它被覆盖
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(10L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "重试等待被中断");
        }
    }

    private static boolean isRetryable(SQLException e) {
        return e.getErrorCode() == ER_LOCK_DEADLOCK
                || e.getErrorCode() == ER_LOCK_WAIT_TIMEOUT
                || SQLSTATE_SERIALIZATION_FAILURE.equals(e.getSQLState());
    }

    /**
     * 把数据库约束冲突翻译成业务错误码。
     *
     * <p>这一步不能省：数据库约束是"最后防线"，它拦住的写入说明应用层校验漏了分支或
     * 状态已损坏。若直接把 {@link SQLException} 抛出去，调用方只能收到 500，
     * 既拿不到可读原因，也无法与真正的程序缺陷区分。见 {@code DECISIONS.md} D-10。
     */
    private static RuntimeException translate(SQLException e) {
        String message = String.valueOf(e.getMessage());

        if (e.getErrorCode() == ER_DUP_ENTRY) {
            return new BizException(
                    ErrorCode.CONFLICT, "唯一约束冲突（重复提交或记录已存在）", Map.of("raw", message));
        }
        if (e.getErrorCode() == ER_CHECK_CONSTRAINT_VIOLATED) {
            if (message.contains("ck_wallets_available_nonneg")
                    || message.contains("ck_participants_frozen_nonneg")
                    || message.contains("ck_wallets_frozen_nonneg")
                    || message.contains("ck_wallets_total_nonneg")
                    || message.contains("ck_ledger_after_nonneg")) {
                return new BizException(
                        ErrorCode.INSUFFICIENT_BALANCE,
                        "资金约束被违反：该操作会使可用余额变为负数",
                        Map.of("raw", message));
            }
            return new BizException(
                    ErrorCode.VALIDATION_FAILED, "字段取值违反约束", Map.of("raw", message));
        }
        if (e.getErrorCode() == ER_NO_REFERENCED_ROW) {
            return new BizException(ErrorCode.NOT_FOUND, "引用了不存在的记录", Map.of("raw", message));
        }
        return new BizException(ErrorCode.INTERNAL_ERROR, "数据库操作失败", Map.of("raw", message));
    }
}
