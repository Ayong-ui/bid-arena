package com.bidarena;

import com.bidarena.bootstrap.DatabaseBootstrap;
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
          DatabaseBootstrap.start();
          app.enableWebSocket(true);
        });
  }
}
