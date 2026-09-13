package com.bidarena.identity.domain;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Map;

/**
 * 用户角色。取值与 {@code openapi.yaml} 的 {@code User.role} 及迁移里的 CHECK 约束一致。
 *
 * <p>只有两个角色是刻意的：本项目的权限边界只有"管理拍品"与"参与竞拍"两种，
 * 多一个角色就要多一套授权规则去验证，而收益为零。
 */
public enum UserRole {

    /** 管理员：创建 / 开始 / 取消拍品，签发 Agent Token。 */
    ADMIN,

    /** 普通用户：加入拍卖、出价、查看自己的钱包与流水。 */
    BIDDER;

    /** 解析数据库里的角色字符串。未知取值一律视为数据损坏，不能默默降级成 BIDDER。 */
    public static UserRole parse(String raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "用户角色为空，数据已损坏");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未知的用户角色", Map.of("role", raw));
        }
    }
}
