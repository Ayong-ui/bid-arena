package com.bidarena;

import com.bidarena.auction.application.SettlementScheduler;
import com.bidarena.bootstrap.DatabaseBootstrap;
import com.bidarena.bootstrap.Services;
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

          // 到期结算必须由服务端自己完成，不依赖任何客户端调用。
          // 这里只启动驱动器；它每轮都回数据库查"到期未结算"，因此重启/多实例都安全。
          SettlementScheduler scheduler =
              new SettlementScheduler(
                  services.settlement,
                  DatabaseBootstrap.intOr("SETTLE_SCAN_INTERVAL_MS", 1000),
                  DatabaseBootstrap.intOr("SETTLE_BATCH_SIZE", 50));
          scheduler.start();
          Runtime.getRuntime().addShutdownHook(new Thread(scheduler::stop, "settlement-shutdown"));

          app.enableWebSocket(true);
        });
  }
}
