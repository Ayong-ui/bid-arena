package com.bidarena.identity.domain;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Map;

/**
 * 用户状态。被禁用的用户不能登录，但数据（钱包、历史出价）必须保留。
 *
 * <p>用状态而不是删除用户：成交记录、资金流水、出价都外键引用用户，
 * 硬删除会连带破坏对账依据。禁用是可逆的，删除不是。
 */
public enum UserStatus {

    ACTIVE,

    DISABLED;

    public static UserStatus parse(String raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "用户状态为空，数据已损坏");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未知的用户状态", Map.of("status", raw));
        }
    }
}
