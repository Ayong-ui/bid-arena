package com.bidarena.auction.application;

import com.bidarena.auction.persistence.AuctionRepository;
import com.bidarena.auction.persistence.AuctionRepository.AuctionRow;
import com.bidarena.auction.persistence.AuctionRepository.ParticipantRow;
import com.bidarena.auction.application.AuctionViews;
import com.bidarena.auction.domain.AuctionEvent;
import com.bidarena.auction.domain.AuctionEventPublisher;
import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.shared.ActorType;
import com.bidarena.shared.BizException;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拍卖的写操作：创建、开始、取消、加入。
 *
 * <h2>为什么这些方法只返回结果标识，不返回快照</h2>
 * 写方法只负责把状态改对；"改完之后长什么样"由 {@link AuctionQueryService} 回答。
 * 若这里顺手返回快照，就有两个地方各自组装 {@code AuctionSnapshot}，
 * 迟早会出现字段不一致（一个加了 {@code serverTime}、另一个忘了）。
 *
 * <h2>出价不在这里</h2>
 * 出价是唯一同时改价格与资金的操作，它有独立的事务与锁顺序（见 {@link BidService}），
 * 不与其他写操作混在一个类里，以免有人以为"改拍卖行"的规则是通用的。
 */
public class AuctionCommandService {

    private static final Logger log = LoggerFactory.getLogger(AuctionCommandService.class);

    /** 参与类型。HTTP 用户为 HUMAN；Agent 出价在 P5 用 AGENT，使运营能区分两类参与者。 */
    public static final String PARTICIPANT_HUMAN = ActorType.HUMAN.name();

    /**
     * Agent 参与类型。Agent 没有独立的加入接口：它在**出价事务内**以该类型补上参与记录
     * （见 {@link BidService#placeBid(String, String, long, String, String)}，D-30）。
     */
    public static final String PARTICIPANT_AGENT = ActorType.AGENT.name();

    private static final int TITLE_MAX = 120;
    private static final int DESCRIPTION_MAX = 2000;
    private static final int DURATION_MIN_SECONDS = 10;
    private static final int DURATION_MAX_SECONDS = 86_400;

    private final DataSource dataSource;
    private final AuctionRepository auctions;
    private final SettlementService settlement;
    private final AuctionEventPublisher events;
    private final long finalGameWindowSeconds;

    public AuctionCommandService(DataSource dataSource, AuctionRepository auctions, SettlementService settlement,
            AuctionEventPublisher events, long finalGameWindowSeconds) {
        this.dataSource = dataSource;
        this.auctions = auctions;
        this.settlement = settlement;
        this.events = events;
        this.finalGameWindowSeconds = finalGameWindowSeconds;
    }

    /**
     * 创建拍品请求。与契约 {@code CreateAuctionRequest} 一致。
     *
     * <p>{@code startsAt} 是可选的**预告开拍时间**：填了就是"到点自动开拍"，
     * 不填就退化成旧行为（等管理员手动开始）。保留旧行为是刻意的——
     * 演示、临时加场这些场景不需要排期，逼着填一个时间反而多一步。
     */
    public record CreateAuction(
            String title, String description, long startPrice, long minIncrement, int durationSeconds,
            Instant startsAt) {}

    /**
     * 创建一件 {@code DRAFT} 拍品，返回生成的 ID。
     *
     * <p>ID 由服务端生成而不是客户端提供：若允许客户端指定主键，两个客户端就能用同一个 ID
     * 互相覆盖对方的拍品，而且这类冲突发生在主键上，错误信息对用户完全不可解释。
     */
    public String create(CreateAuction command) {
        if (command == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        String title = command.title() == null ? "" : command.title().trim();
        if (title.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "title 不能为空");
        }
        if (title.length() > TITLE_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "title 过长",
                    Map.of("maxLength", TITLE_MAX, "actual", title.length()));
        }
        String description = command.description() == null ? "" : command.description().trim();
        if (description.length() > DESCRIPTION_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "description 过长",
                    Map.of("maxLength", DESCRIPTION_MAX, "actual", description.length()));
        }
        if (command.startPrice() < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "startPrice 必须 >= 1",
                    Map.of("startPrice", command.startPrice()));
        }
        if (command.minIncrement() < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "minIncrement 必须 >= 1",
                    Map.of("minIncrement", command.minIncrement()));
        }
        if (command.durationSeconds() < DURATION_MIN_SECONDS || command.durationSeconds() > DURATION_MAX_SECONDS) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "durationSeconds 必须在 " + DURATION_MIN_SECONDS + ".." + DURATION_MAX_SECONDS + " 之间",
                    Map.of("durationSeconds", command.durationSeconds()));
        }

        String id = "auc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant startsAt = command.startsAt() == null ? null : command.startsAt();
        if (startsAt != null) {
            // 用数据库时间而不是本机时钟比较（D-5）：多实例/容器漂移时，
            // 一个实例认为"还有 30 秒"、另一个认为"已经过了"，会让"预告"这件事变得不可解释。
            Instant now = auctions.currentDbTime();
            if (!startsAt.isAfter(now)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "startsAt 必须晚于当前时间（否则请留空，改为手动开始）",
                        Map.of("startsAt", startsAt.toString(), "serverTime", now.toString()));
            }
        }
        auctions.insertOutsideTx(id, title, description, command.startPrice(), command.minIncrement(),
                command.durationSeconds(), startsAt);
        return id;
    }

    /**
     * 开始拍卖。截止时间 = 数据库当前时间 + 拍品时长，在锁内计算（见
     * {@link AuctionRepository#startNow}）。
     *
     * <p>重复开始会抛 {@code INVALID_STATE} 而不是返回已有截止时间：
     * "开始"是一次有副作用的指令，第二次调用没有生效，必须让调用方知道。
     */
    public void start(String auctionId) {
        AuctionRepository.Started started = auctions.startNow(auctionId);
        // 开拍是一次状态变更（DRAFT → RUNNING），产生新版本号。
        // 广播完整快照而不是一个“已开始”信号：此刻拍卖的每个字段都变了
        // （状态、截止时间、版本号），订阅者直接拿到可替换的权威状态，不必自己拼。
        publishQuietly(AuctionEvents.snapshot(
                AuctionViews.Snapshot.of(started.auction(), started.serverTime(), finalGameWindowSeconds)));
    }

    /**
     * 自动开拍：把预告时间已到的拍品转成 {@code RUNNING}。返回实际开拍的场数。
     *
     * <p>与 {@link #start(String)} 走完全相同的路径，因此广播、状态转换条件、
     * 截止时间计算都没有第二套实现——"管理员点的开始"与"时间到了自己开始"在这一层是同一件事。
     *
     * <p>逐场隔离异常：某一场因为并发（管理员抢先手动了）而转换失败，不该让同批的其他场次一起失败。
     */
    public int startDueScheduled(int batchSize) {
        List<String> due = Db.read(dataSource, conn -> auctions.findDueToStartIds(conn, Db.now(conn), batchSize));
        int started = 0;
        for (String auctionId : due) {
            try {
                start(auctionId);
                started++;
                log.info("预告到点，自动开拍 auction={}", auctionId);
            } catch (BizException e) {
                // INVALID_STATE：管理员恰好同时点了开始，或已被取消——都不是错误。
                log.info("自动开拍跳过 auction={} 原因={}", auctionId, e.code());
            } catch (RuntimeException e) {
                log.warn("自动开拍失败 auction={}: {}", auctionId, e.getMessage());
            }
        }
        return started;
    }

    /** 取消拍卖并释放全部冻结。委托给 {@link SettlementService#cancel}，使取消与到期结算共用同一套资金逻辑。 */
    public void cancel(String auctionId) {
        settlement.cancel(auctionId);
    }

    /**
     * 加入拍卖间，返回参与记录。重复加入是幂等的（{@code ON DUPLICATE KEY UPDATE}），
     * 因此"刷新页面重新加入"不会报错，也不会改变首次的 {@code joinedAt}。
     *
     * <p>在事务里先锁拍卖行再写参与记录：若拍卖已结束或已取消，加入没有意义，
     * 且会让"参与者"这个集合在结算之后继续增长，破坏"结算时遍历本场全部参与者"的前提。
     */
    public AuctionViews.Participant join(String auctionId, String userId) {
        JoinOutcome outcome = Db.tx(dataSource, conn -> {
            AuctionRow auction = auctions.lockAuction(conn, auctionId);
            if (auction == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "拍卖不存在", Map.of("auctionId", auctionId));
            }
            if (auction.status() == AuctionStatus.FINISHED || auction.status() == AuctionStatus.CANCELLED) {
                throw new BizException(ErrorCode.INVALID_STATE, "拍卖已结束，无法加入",
                        Map.of("auctionId", auctionId, "status", auction.status().name()));
            }
            // 先判断是否首次加入，再写：只有首次加入才是状态变更，才能推进版本号。
            // 重复加入若也推进 seq，任何登录用户都能靠连点 join 让所有客户端不停重新拉快照。
            boolean firstJoin = !auctions.isParticipant(conn, auctionId, userId);
            auctions.join(conn, auctionId, userId, PARTICIPANT_HUMAN);
            ParticipantRow joined = auctions.findParticipant(conn, auctionId, userId);
            if (joined == null) {
                // 刚刚写入却读不到，只可能是写入被静默吞掉了（例如 INSERT IGNORE 的副作用）。
                // 这里必须报错：否则调用方会拿着一个不存在的参与记录继续出价，最后死在 NOT_JOINED。
                throw new BizException(ErrorCode.INTERNAL_ERROR, "加入后无法读取参与记录",
                        Map.of("auctionId", auctionId, "userId", userId));
            }
            long seq = firstJoin ? auctions.bumpSeq(conn, auctionId) : auction.seq();
            return new JoinOutcome(joined, seq, auctions.countParticipants(conn, auctionId), firstJoin);
        });
        if (outcome.firstJoin()) {
            publishQuietly(AuctionEvents.participantJoined(auctionId, outcome.seq(), userId,
                    outcome.participantCount(), outcome.joined().joinedAt()));
        }
        return AuctionViews.Participant.of(outcome.joined());
    }

    /** 一次加入事务的结果：参与记录 + 新版本号 + 参与人数 + 是否首次加入。 */
    private record JoinOutcome(ParticipantRow joined, long seq, int participantCount, boolean firstJoin) {}

    /**
     * 广播的兜底：事件在事务**提交后**发布，推送失败不能推翻已经提交的状态变更（契约 §7）。
     */
    private void publishQuietly(AuctionEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            log.warn("事件广播失败，已提交的状态变更不受影响 auction={} type={}: {}",
                    event.auctionId(), event.type(), e.getMessage());
        }
    }
}
