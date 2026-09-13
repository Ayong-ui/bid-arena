package com.bidarena.wallet.application;

import com.bidarena.shared.ApiTime;
import com.bidarena.wallet.persistence.WalletRepository.LedgerRow;
import com.bidarena.wallet.persistence.WalletRepository.WalletRow;

/**
 * 钱包上下文的对外投影。字段与 {@code openapi.yaml} 的 {@code Wallet} / {@code LedgerEntry} 一致。
 *
 * <p>{@code availableBalance} 是**算出来**的，不是查出来的：可用额 = 总额 - 冻结额，
 * 只有算出来的那个才不可能与另外两个字段不一致（见 {@code DESIGN.md} 的 INV-1）。
 * {@code LedgerEntry} 带 {@code requestId}，使每一条资金变动都能追回到具体请求——
 * 原文要求流水含“关联请求”，这是对账时唯一能确认“这次冻结是哪次出价造成的”的线索。
 */
public final class WalletViews {

    private WalletViews() {}

    public record Wallet(long totalBalance, long frozenAmount, long availableBalance) {

        public static Wallet of(WalletRow row) {
            return new Wallet(row.totalBalance(), row.frozenAmount(), row.availableBalance());
        }
    }

    public record LedgerEntry(
            String id, String type, long amount, String auctionId, String requestId, String createdAt) {

        public static LedgerEntry of(LedgerRow row) {
            return new LedgerEntry(
                    String.valueOf(row.id()),
                    row.type().name(),
                    row.amount(),
                    row.auctionId(),
                    row.requestId(),
                    ApiTime.format(row.createdAt()));
        }
    }
}
