package com.bidarena.identity.adapter;

import com.bidarena.identity.application.PasswordHasher;
import org.mindrot.jbcrypt.BCrypt;

/**
 * BCrypt 密码校验。
 *
 * <p>选 BCrypt 而不是 SHA-256/MD5：后两者快，快就意味着可被 GPU 高速爆破；
 * BCrypt 的 cost 参数让每次校验固定地慢，把离线爆破的成本抬高到不可行。
 * 迁移里的种子哈希是 cost=10 生成的，可直接校验。
 *
 * <h2>为什么哈希非法时返回 false 而不是抛异常</h2>
 * 校验一个格式损坏的哈希，结果只能是"这个密码对不上"，它和密码错误在语义上没有区别。
 * 若这里抛异常，一个被人工写坏的哈希会让登录接口返回 500（而不是 401），
 * 从而把"这个账号存在但数据有问题"泄漏出去——又是账号枚举。
 */
public class BCryptPasswordHasher implements PasswordHasher {

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // 哈希前缀/盐格式非法，属于数据问题；对外与"密码不对"同义。
            return false;
        }
    }
}
