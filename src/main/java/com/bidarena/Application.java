package com.bidarena;

import com.bidarena.api.ApiExceptionFilter;
import com.bidarena.api.AuthFilter;
import com.bidarena.api.CorsFilter;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.auction.application.BidService;
import com.bidarena.auction.application.SettlementScheduler;
import com.bidarena.bootstrap.DatabaseBootstrap;
import com.bidarena.bootstrap.Env;
import com.bidarena.bootstrap.Services;
import com.bidarena.identity.application.IdentityService;
import com.bidarena.wallet.application.WalletQueryService;
import java.util.Arrays;
import java.util.List;
import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain
public class Application {
  public static void main(String[] args) {
    Solon.start(
        Application.class,
        args,
        app -> {
          // 组合根：先建连接池并迁移到最新 schema，失败则终止启动。
          // 放在监听端口之前，保证对外可服务时表结构一定是确定的。
          var dataSource = DatabaseBootstrap.start();
          Services services = Services.wire(dataSource);

          // —— 注册表实例 ——
          // 必须在 beanScan 之前放进容器：控制器用 @Inject 字段引用它们，
          // 而 Solon 的启动顺序是「回调 -> beanScan」，放到回调之后就没有意义了。
          // 这里刻意逐个按类型注册，而不是给应用服务加 @Component 注解：
          // 注解会把某个类的构造函数签名钉死在框架的解析规则上，
          // 显式注册让「谁拿到了哪个实例」在组合根一目了然。
          app.context().wrapAndPut(BidService.class, services.bids);
          app.context().wrapAndPut(IdentityService.class, services.identity);
          app.context().wrapAndPut(WalletQueryService.class, services.walletQueries);
          app.context().wrapAndPut(AuctionQueryService.class, services.auctionQueries);
          app.context().wrapAndPut(AuctionCommandService.class, services.auctionCommands);

          // —— 过滤器 ——
          // 数值越小越靠外层。顺序是刻意的：
          // 1) ApiExceptionFilter 必须最外层，才能接住后面所有过滤器与路由抛出的异常；
          // 2) CorsFilter 在鉴权之前，因为浏览器的预检请求不带凭证，被鉴权拦下就永远过不了；
          // 3) AuthFilter 在业务之前。
          app.filter(0, new ApiExceptionFilter());
          app.filter(1, new CorsFilter(parseOrigins()));
          app.filter(2, new AuthFilter(services.tokens));

          // 到期结算必须由服务端自己完成，不依赖任何客户端调用。
          // 这里只启动驱动器；它每轮都回数据库查"到期未结算"，因此重启/多实例都安全。
          SettlementScheduler scheduler =
              new SettlementScheduler(
                  services.settlement,
                  Env.intOr("SETTLE_SCAN_INTERVAL_MS", 1000),
                  Env.intOr("SETTLE_BATCH_SIZE", 50));
          scheduler.start();
          Runtime.getRuntime().addShutdownHook(new Thread(scheduler::stop, "settlement-shutdown"));

          app.enableWebSocket(true);
        });
  }

  /**
   * 解析 {@code CORS_ORIGINS}（逗号分隔）。
   *
   * <p>未配置时返回**空列表**而不是 {@code *}：默认放开跨域意味着任何网站都能用访客的
   * 浏览器替他们出价。开放是显式动作，必须由部署者写出来。
   */
  private static List<String> parseOrigins() {
    String raw = Env.read("CORS_ORIGINS");
    if (raw == null) {
      return List.of();
    }
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }
}
