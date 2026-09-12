package com.bidarena;
import java.time.*;
public class AuctionEngineTest {
  public static void main(String[] args){
    AuctionEngine e=new AuctionEngine(); e.createUser("a"); e.createUser("b"); e.createAuction("x"); Instant t=Instant.parse("2026-01-01T00:00:00Z"); e.start("x",t,Duration.ofSeconds(180)); e.join("x","a"); e.join("x","b");
    var r=e.bid("x","a","r1",110,t.plusSeconds(1)); assert r.accepted(); assert e.available("a")==890;
    assert e.bid("x","a","r1",110,t.plusSeconds(2)).idempotent(); assert e.bid("x","b","r2",115,t.plusSeconds(3)).reason().equals("TOO_LOW");
    var s=e.settle("x",t.plusSeconds(181)); assert s.winner().equals("a"); assert e.available("a")==890; System.out.println("AuctionEngineTest: PASS");
  }
}
