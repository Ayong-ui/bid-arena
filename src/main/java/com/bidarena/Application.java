package com.bidarena;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain
public class Application {
  public static void main(String[] args) {
    Solon.start(Application.class, args, app -> app.enableWebSocket(true));
  }
}
