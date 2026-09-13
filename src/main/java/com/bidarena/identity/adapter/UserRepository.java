package com.bidarena.identity.adapter;

import com.bidarena.identity.domain.User;
import com.bidarena.identity.domain.UserRole;
import com.bidarena.identity.domain.UserStatus;
import com.bidarena.shared.Db;
import javax.sql.DataSource;

/**
 * {@code users} 表的持久化。只读——用户由迁移脚本播种，运行时没有注册接口。
 *
 * <p>{@link StoredUser} 是本项目里**唯一**携带 {@code passwordHash} 的类型，
 * 且只在 {@code IdentityService.login} 内部存活。领域类型 {@link User} 不带哈希，
 * 因此哈希不可能被无意间序列化进响应或写进日志。
 */
public class UserRepository {

    private final DataSource dataSource;

    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 带密码哈希的完整行。{@link #toUser()} 剥掉哈希，得到可外传的领域对象。 */
    public record StoredUser(
            String id, String email, String displayName, String passwordHash, UserRole role, UserStatus status) {

        public boolean isActive() {
            return status == UserStatus.ACTIVE;
        }

        public User toUser() {
            return new User(id, email, displayName, role, status);
        }
    }

    private static final String SELECT =
            "SELECT id, email, display_name, password_hash, role, status FROM users ";

    /**
     * 按邮箱查用户。比较交给数据库（{@code utf8mb4_0900_ai_ci} 本身就是大小写不敏感），
     * 不在 Java 里做 {@code toLowerCase}：那会让"数据库怎么比"与"代码怎么比"出现两套规则，
     * 且 {@code toLowerCase} 受默认 Locale 影响（土耳其语的 i 问题）。
     */
    public StoredUser findByEmail(String email) {
        return Db.read(dataSource, conn -> Db.queryOne(conn, SELECT + "WHERE email = ?",
                UserRepository::map, email));
    }

    public StoredUser findById(String id) {
        return Db.read(dataSource, conn -> Db.queryOne(conn, SELECT + "WHERE id = ?",
                UserRepository::map, id));
    }

    private static StoredUser map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredUser(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                UserRole.parse(rs.getString("role")),
                UserStatus.parse(rs.getString("status")));
    }
}
