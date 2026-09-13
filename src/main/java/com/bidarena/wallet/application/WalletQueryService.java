package com.bidarena.wallet.application;

import com.bidarena.api.PageQuery;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.wallet.adapter.WalletRepository;
import com.bidarena.wallet.adapter.WalletRepository.LedgerRow;
import com.bidarena.wallet.adapter.WalletRepository.WalletRow;
import com.bidarena.wallet.adapter.WalletViews;
import java.util.List;
import java.util.Map;

/**
 * 钱包与流水的只读查询。
 *
 * <p>只有读，没有任何写方法：本项目里能改变钱包的**只有** {@code BidService} 与
 * {@code SettlementService} 这两个事务。把读单独放一个类，让"谁能动钱"这件事在
 * 代码结构上一眼可见——如果查询服务也能写，这个边界就只存在于文档里了。
 */
public class WalletQueryService {

    private final WalletRepository wallets;

    public WalletQueryService(WalletRepository wallets) {
        this.wallets = wallets;
    }

    public WalletViews.Wallet currentWallet(String userId) {
        WalletRow row = wallets.load(userId);
        if (row == null) {
            // 用户存在但钱包不存在属于数据损坏（V2 迁移为每个用户建钱包）。
            // 返回 404 而不是 500：对客户端而言这是"你的钱包不存在"，可解释、可排查。
            throw new BizException(ErrorCode.NOT_FOUND, "钱包不存在", Map.of("userId", userId));
        }
        return WalletViews.Wallet.of(row);
    }

    public PageQuery.Page<WalletViews.LedgerEntry> ledger(String userId, PageQuery page) {
        List<LedgerRow> rows = wallets.pageLedger(userId, page.limit(), page.offset());
        long total = wallets.countLedger(userId);
        return new PageQuery.Page<>(
                rows.stream().map(WalletViews.LedgerEntry::of).toList(), page.page(), page.size(), total);
    }
}
