import io
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
R = "src/main/java/com/bidarena/"

# (标的, 文件, 变异体, 期望失败的规则字段名)
MUTATIONS = [
    ("A1", R + "auction/domain/AuctionEventType.java",
     'static Object archLeak() { return org.slf4j.LoggerFactory.getLogger("arch"); }',
     ["domainDependsOnlyOnItselfAndTheSharedKernel"]),
    ("A2", R + "auction/application/AuctionCommandService.java",
     'static Class<?> archLeak() { return com.bidarena.auction.adapter.AuctionSocketHandler.class; }',
     ["applicationLayerDoesNotDependOnInboundAdaptersOrBootstrap"]),
    ("A3", R + "wallet/persistence/WalletRepository.java",
     'static Class<?> archLeak() { return com.bidarena.wallet.application.WalletQueryService.class; }',
     ["persistenceLayerDoesNotDependOnApplicationOrAdapters"]),
    ("A4", R + "wallet/adapter/HttpWalletController.java",
     'static Class<?> archLeak() { return com.bidarena.bootstrap.Env.class; }',
     ["inboundAdaptersDoNotDependOnBootstrap"]),
    ("A5", R + "shared/ErrorCode.java",
     'static Class<?> archLeak() { return com.bidarena.identity.domain.Principal.class; }',
     ["sharedKernelDoesNotDependOnContexts"]),
    ("A6", R + "auction/domain/AuctionEvent.java",
     'static Class<?> archLeak() { return com.bidarena.identity.domain.Principal.class; }',
     ["domainModelsOfDifferentContextsDoNotDependOnEachOther"]),
    ("A7", R + "wallet/adapter/HttpWalletController.java",
     'static Class<?> archLeak2() { return com.bidarena.auction.adapter.HttpAuctionController.class; }',
     ["adaptersOfDifferentContextsDoNotDependOnEachOther"]),
    # auction -> wallet 的依赖已经存在（BidService 用 WalletRepository），
    # 因此环只能靠 wallet -> auction 这个方向造出来。
    ("A8", R + "wallet/persistence/WalletRepository.java",
     'static Class<?> archLeak() { return com.bidarena.auction.persistence.AuctionRepository.class; }',
     ["contextsAreFreeOfCycles"]),
    # 层与层的环：auction.application -> wallet.persistence 已存在（BidService 用 WalletRepository），
    # 反向加一条 wallet.persistence -> auction.application 就绕回去了。这个变异同时会
    # 触发"出站适配器不得依赖应用层"，属于同一处坏味道的两个侧面。
    ("A9", R + "wallet/persistence/WalletRepository.java",
     'static Class<?> archLeak() { return com.bidarena.auction.application.AuctionQueryService.class; }',
     ["layersAreFreeOfCycles"]),
]

REPORT = "target/surefire-reports/com.bidarena.architecture.ArchitectureTest.txt"
# 同 agent_mutation_check：还原源码后 class 会比源码新，增量编译不会重编，
# 上一条变异会残留在 classpath 上。clean 保证每条变异从干净基线出发。
CMD = "mvn -o clean test -Dtest=ArchitectureTest -DfailIfNoSpecifiedTests=false"


def inject(path, snippet):
    text = io.open(path, encoding="utf-8").read().rstrip()
    assert text.endswith("}"), path
    io.open(path, "w", encoding="utf-8").write(text[:-1] + "\n    " + snippet + "\n}\n")


def main():
    os.chdir(ROOT)
    verdicts = []
    for label, rel, snippet, rules in MUTATIONS:
        path = os.path.join(ROOT, rel.replace("/", os.sep))
        assert os.path.exists(path), path
        if os.path.exists(REPORT):
            os.remove(REPORT)
        shutil.copy(path, path + ".bak")
        try:
            inject(path, snippet)
            proc = subprocess.run(CMD, shell=True, capture_output=True, text=True, errors="replace")
            report = io.open(REPORT, encoding="utf-8").read() if os.path.exists(REPORT) else ""
            fired = re.findall(r"ArchitectureTest\.(\w+) -- Time", report)
            hit = [r for r in rules if r in fired]
            killed = proc.returncode != 0 and len(hit) == len(rules)
            verdicts.append((label, killed))
            print("%s %-7s rc=%d hit=%s fired=%s" % ("KILLED  " if killed else "SURVIVED", label,
                                                     proc.returncode, ",".join(hit) or "NONE",
                                                     ",".join(fired) or "NONE"))
        finally:
            shutil.move(path + ".bak", path)
            # 还原后把 mtime 拨到现在：否则“变异后的 class”比“还原后的源码”新，
            # Maven 增量编译会跳过重编，上一条变异的 class 会留在 classpath 上毒害后续结论。
            os.utime(path, None)
    print("---- total: %d/%d KILLED" % (sum(1 for _, k in verdicts if k), len(verdicts)))
    return 0 if all(k for _, k in verdicts) else 1


sys.exit(main())
