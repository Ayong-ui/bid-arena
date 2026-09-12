package com.bidarena;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 可替换持久化实现的拍卖规则核心；生产环境应在 MySQL 事务中实现同样的不变量。 */
public final class AuctionEngine {
  public enum Status { DRAFT, RUNNING, SETTLING, FINISHED, CANCELLED }
  public record BidResult(boolean accepted, boolean idempotent, String reason, long price, String leader, int extensions) {}
  public record Settlement(String auctionId, String winner, long price, String reason) {}
  private static final class Wallet { long total = 1000, frozen; }
  private static final class Auction { final String id; Status status=Status.DRAFT; long price=100, min=10; String leader; Instant endsAt; int extensions; final Map<String,Long> frozen=new HashMap<>(); final Set<String> requests=new HashSet<>();
    Auction(String id){this.id=id;}
  }
  private final Map<String,Wallet> wallets = new ConcurrentHashMap<>();
  private final Map<String,Auction> auctions = new ConcurrentHashMap<>();
  private final Map<String,Settlement> settlements = new ConcurrentHashMap<>();

  public void createUser(String user){ wallets.putIfAbsent(user,new Wallet()); }
  public void createAuction(String id){ auctions.putIfAbsent(id,new Auction(id)); }
  public synchronized void start(String id, Instant now, Duration duration){ Auction a=get(id); if(a.status!=Status.DRAFT) throw new IllegalStateException("not draft"); a.status=Status.RUNNING; a.endsAt=now.plus(duration); }
  public synchronized void join(String id,String user){ get(id); createUser(user); }
  public synchronized BidResult bid(String id,String user,String requestId,long amount,Instant received){
    Auction a=get(id); createUser(user); if(a.requests.contains(user+":"+requestId)) return new BidResult(true,true,"IDEMPOTENT",a.price,a.leader,a.extensions);
    if(a.status!=Status.RUNNING) return reject(a,"NOT_RUNNING"); if(!received.isBefore(a.endsAt)) return reject(a,"LATE"); if(amount<a.price+a.min) return reject(a,"TOO_LOW");
    long old = a.frozen.getOrDefault(user,0L), add=amount-old; Wallet w=wallets.get(user); if(add> w.total-w.frozen) return reject(a,"INSUFFICIENT_BALANCE");
    if(a.leader!=null){ Wallet previous=wallets.get(a.leader); previous.frozen-=a.frozen.getOrDefault(a.leader,0L); a.frozen.put(a.leader,0L); }
    w.frozen+=add; a.frozen.put(user,amount); a.price=amount; a.leader=user; a.requests.add(user+":"+requestId);
    if(Duration.between(received,a.endsAt).compareTo(Duration.ofSeconds(5))<=0 && a.extensions<3){a.endsAt=a.endsAt.plusSeconds(10);a.extensions++;}
    return new BidResult(true,false,"BID_ACCEPTED",a.price,a.leader,a.extensions);
  }
  public synchronized Settlement settle(String id,Instant now){ Auction a=get(id); if(settlements.containsKey(id)) return settlements.get(id); if(a.status!=Status.RUNNING && a.status!=Status.SETTLING) throw new IllegalStateException("not settleable"); if(a.status==Status.RUNNING && now.isBefore(a.endsAt)) throw new IllegalStateException("not expired"); a.status=Status.SETTLING;
    if(a.leader!=null){ Wallet winner=wallets.get(a.leader); winner.frozen-=a.frozen.getOrDefault(a.leader,0L); winner.total-=a.price; }
    for(var e:a.frozen.entrySet()) if(!Objects.equals(e.getKey(),a.leader)){wallets.get(e.getKey()).frozen-=e.getValue();}
    Settlement s=new Settlement(id,a.leader,a.leader==null?0:a.price,a.leader==null?"NO_BIDS":"TIMEOUT"); settlements.put(id,s); a.status=Status.FINISHED; return s;
  }
  public synchronized void cancel(String id){ Auction a=get(id); if(a.status!=Status.DRAFT&&a.status!=Status.RUNNING) throw new IllegalStateException("cannot cancel"); for(var e:a.frozen.entrySet()) wallets.get(e.getKey()).frozen-=e.getValue(); a.status=Status.CANCELLED; }
  public long available(String user){ Wallet w=wallets.get(user); return w.total-w.frozen; }
  private BidResult reject(Auction a,String reason){return new BidResult(false,false,reason,a.price,a.leader,a.extensions);}
  private Auction get(String id){ Auction a=auctions.get(id); if(a==null) throw new NoSuchElementException(id); return a; }
}
