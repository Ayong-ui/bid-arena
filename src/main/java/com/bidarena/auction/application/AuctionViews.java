package com.bidarena.auction.application;

import com.bidarena.shared.ApiTime;
import com.bidarena.auction.persistence.AuctionRepository.AuctionRow;
import com.bidarena.auction.persistence.AuctionRepository.BidRow;
import com.bidarena.auction.persistence.AuctionRepository.ParticipantRow;
import java.time.Instant;

/**
 * 拍卖上下文的对外投影。字段与 {@code openapi.yaml} 的
 * {@code AuctionSnapshot} / {@code Bid} / {@code Participant} / {@code AuctionResult} 一致。
 *
 * <p>与 {@code UserView} 同样的理由：不让仓储行类型直接变成响应体。
 * 仓储行会随查询需要加列（例如内部诊断字段），而响应格式必须显式受控。
 *
 * <p>所有时间都转成字符串（见 {@link ApiTime}），不让"序列化器怎么输出时间"变成隐式契约。
 */
public final class AuctionViews {

    private AuctionViews() {}

    /** {@code AuctionSnapshot}。{@code serverTime} 由调用方传入，使同一页里所有快照共享一个时间基准。 */
    public record Snapshot(
            String id,
            String title,
            String description,
            String status,
            long startPrice,
            long minIncrement,
            long currentPrice,
            String leader,
            String endsAt,
            int extensionCount,
            int participantCount,
            long seq,
            String serverTime) {

        public static Snapshot of(AuctionRow row, Instant serverTime) {
            return new Snapshot(
                    row.id(),
                    row.title(),
                    row.description(),
                    row.status().name(),
                    row.startPrice(),
                    row.minIncrement(),
                    row.currentPrice(),
                    row.leaderId(),
                    ApiTime.format(row.endsAt()),
                    row.extensionCount(),
                    row.participantCount(),
                    row.seq(),
                    ApiTime.format(serverTime));
        }
    }

    /** 一条出价。{@code seq} 单调递增，客户端据此检测丢事件（见 {@code docs/REALTIME_AND_COMMAND_FLOW.md}）。 */
    public record Bid(String id, String userId, long amount, String requestId, long seq, String serverTime) {

        public static Bid of(BidRow row) {
            return new Bid(String.valueOf(row.id()), row.userId(), row.amount(), row.requestId(), row.seq(),
                    ApiTime.format(row.serverTime()));
        }
    }

    public record Participant(String auctionId, String userId, String joinedAt) {

        public static Participant of(ParticipantRow row) {
            return new Participant(row.auctionId(), row.userId(), ApiTime.format(row.joinedAt()));
        }
    }

    /** {@code AuctionResult}。{@code status} 取拍卖的当前状态（FINISHED 或 CANCELLED）。 */
    public record Result(
            String auctionId, String status, String winner, long finalPrice, String reason, String settledAt) {}
}
