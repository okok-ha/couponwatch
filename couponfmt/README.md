# couponfmt — 通用兑换码格式分析与本地离线测试工具

一个纯本地运行的 Python 工具：**输入若干兑换码样例 → 自动归纳格式规则 → 生成离线测试数据 → 校验/统计/导出 →（可选）在本地模拟页面上跑浏览器自动化流程**。

规则完全由样例动态推导，代码中**没有写死任何一种兑换码格式**。换一批样例重新分析即可，规则也会随之更新，无需改代码。

> **边界说明**：格式匹配只表示字符串符合归纳出的规则，**不代表兑换码在真实平台上有效**。程序不连接任何真实兑换平台，不猜测、不批量提交、不验证真实兑换码。自动化测试仅针对本机启动的模拟页面（或你明确授权的测试环境）。

---

## 一、GitHub 调研结论

调研方向：兑换码/优惠券码生成、正则驱动的随机串生成、由样例归纳正则、校验位算法、浏览器自动化。

| 项目 | 链接 | 关键做法 | 本项目如何处理 |
|---|---|---|---|
| voucherifyio/voucher-code-generator-js | https://github.com/voucherifyio/voucher-code-generator-js | 用 `count / length / charset / prefix / suffix / pattern` 参数化描述码结构，内部按字符池采样 | **借鉴参数模型**：把"格式"拆成有序字段（字面量 + 字符池 + 长度区间），生成时逐字段采样。该库为 JS 实现，不引入 |
| pemistahl/grex | https://github.com/pemistahl/grex | 从若干样例字符串**归纳**正则（Rust，含 Python 绑定） | **借鉴"由样例归纳"的方法论**：本项目实现逐位/分段归纳，并额外保留字段语义（前缀、分隔符、字符池），比纯正则更利于生成。未引入（需要编译产物，且输出缺少结构信息） |
| asciimoo/exrex | https://github.com/asciimoo/exrex | 正则 → 字符串枚举/随机生成（`generate()`、`getone()`） | 思路参考：先得到正则再反向生成。本项目**未采用**，因为直接按字段采样可以控制去重、长度区间和校验位，且避免引入依赖 |
| leapfrogonline/rstr | https://github.com/leapfrogonline/rstr | 按正则生成随机串 | 同上，仅作思路对照，不引入 |
| arthurdejong/python-stdnum | https://github.com/arthurdejong/python-stdnum | Luhn / Verhoeff / Damm / ISO 7064（MOD 11,2 · 37,2 · 97,10 · 11,10 · 37,36）的成熟实现与测试向量 | **借鉴算法清单与测试向量**：本项目的校验位表（Damm/Verhoeff）与 ISO 7064 计算按公开标准自行实现，并用其公开测试向量（如 Luhn 7992739871→3、Damm 572→4、Verhoeff 236→3）做单元测试。不引入依赖 |
| microsoft/playwright-python | https://github.com/microsoft/playwright-python | 同步/异步浏览器自动化 API，自动等待元素 | **采用**（唯一第三方依赖，且仅自动化模块使用） |
| HypothesisWorks/hypothesis | https://github.com/HypothesisWorks/hypothesis | `from_regex` 策略做属性测试 | 未引入；本项目用标准库 `unittest` 覆盖关键路径，避免测试阶段引入重依赖 |

**结论**：核心功能（分析 / 生成 / 校验 / 报告 / 导出）**零第三方依赖**，只用标准库；`playwright` 仅用于可选的浏览器自动化，缺失时程序给出明确安装提示并支持 `--dry-run` 演练。

---

## 二、技术架构

```
couponfmt/
├── main.py                      # 启动入口（交互菜单 / 子命令）
├── requirements.txt             # 仅 playwright（可选）
├── run.bat                      # Windows 一键启动
├── couponfmt/                   # 核心库（纯标准库）
│   ├── charset.py               # 字符集归类、正则字符类推导（含 Base32/Hex 等命名集打分）
│   ├── checksum.py              # Luhn/Damm/Verhoeff/ISO7064/加权和 + 校验位自动识别
│   ├── models.py                # Field / FormatRule 数据模型，正则与 JSON 序列化
│   ├── inputs.py                # 输入归一化与友好校验
│   ├── analyzer.py              # 格式归纳引擎（三条分析路径）
│   ├── generator.py             # 按规则生成测试码（去重、种子、字符池、校验位补全）
│   ├── validator.py             # 正则 + 校验位校验，逐段错误定位
│   ├── reporter.py              # 长度分布 / 字符频率 / 类别分布 / 匹配率 / 熵
│   ├── exporter.py              # TXT / CSV 导出（CSV 带 BOM，Excel 直接可读）
│   ├── storage.py               # 规则 JSON 保存 / 加载 / 列举
│   └── cli.py                   # 交互式菜单 + argparse 子命令
├── automation/
│   ├── mock_server.py           # 本地模拟兑换页面（仅监听 127.0.0.1）
│   ├── mock_page/index.html     # 模拟页面（输入框 #code / 按钮 #submit / 结果 #result）
│   └── runner.py                # Playwright 运行器：暂停/继续/停止、限次、CSV 报告
├── samples/                     # 4 组样例（分段式 / Luhn / 变长 / 混合）
├── tests/                       # 68 个单元与集成测试
├── rules/                       # 规则配置（运行时生成）
└── out/                         # 生成数据与报告（运行时生成）
```

**数据流**

```
样例文本
  └─ inputs.normalize_codes        去空行/去重/长度与可打印性校验
      └─ analyzer.analyze          三条路径归纳 → FormatRule(fields, checksum, regex)
          ├─ generator.generate    字段采样 → 可选补校验位 → 去重
          ├─ validator.validate    正则匹配 + 校验位 + 逐段错误定位
          ├─ reporter.analyze_codes 统计分布与匹配率
          ├─ exporter.export_*      TXT / CSV
          └─ storage.save_rule      JSON（可跨会话复用）
```

### 2.1 归纳引擎的三条路径

| 模式 | 触发条件 | 做法 | 置信度 |
|---|---|---|---|
| `positional` 定长逐位 | 所有样例等长 | 逐位判断"恒定/可变"；恒定连续位合并为字面量（在字母数字↔符号边界断开）；连续可变位按**整段字符并集**统一归类 | 样本≥5 时 0.94 |
| `segmented` 分段 | 长度不等但分隔符结构一致 | 按分隔符切段，逐段统计长度区间 `{min,max}` | 略低于定长 |
| `coarse` 粗粒度 | 结构与长度都不一致 | 取公共前后缀，其余按整体字符集处理 | ≤0.45 |
| `single` 单样本 | 只有 1 个样例 | 按字符类别切段，仅给出候选规则 | 0.30 |

关键细节：

- **避免正则碎片化**：同一段可变位不再逐位归类，而是取整段并集，因此 `8F3K / QW7M / 4XZ9` 得到 `[A-Z0-9]{4}`，而不是 `[A-Z0-9][A-Z][A-Z0-9]{2}`。
- **只在强证据处切分**：段内仅当两侧字符集互斥且覆盖率 ≥ 0.6 时才再切（例如 10 个样本里该位置全是数字）。
- **字符集打分**：在"包含观测集"的命名字符集（数字/大小写字母/Hex/Base32/Base58…）中按 `覆盖率 × 先验权重` 取最优，冷门字符集降权，避免把 `A,B,C,1,2` 误判成 Base32。
- **双字符类**：每个字段同时保存 `regex_class`（泛化，用于通用校验）与 `strict_class`（仅观测字符，用于严格模式）。

### 2.2 校验位识别

对"去分隔符 + 统一大写"的紧凑串，依次尝试 Luhn、Damm、Verhoeff、ISO 7064 MOD 97-10 / MOD 11-2、加权和（MOD 36 / 32 / 10，位置权重或等权）。
命中要求**全部样本一致**，置信度按 `1 - base^(1-n)` 估计（base 为校验字符集大小），样本少于 3 个直接跳过探测，避免小样本巧合。识别到校验位后，生成阶段会自动补算校验位，使生成的测试码自身也能通过校验。

---

## 三、Windows 运行教程

### 步骤 1：确认 Python

打开 PowerShell 或 CMD：

```bat
python --version
```

需要 **Python 3.9+**（开发与自检环境为 3.13）。若提示找不到命令，安装时请勾选 "Add Python to PATH"。

### 步骤 2：进入项目目录

```bat
cd /d "C:\Users\1\WorkBuddy AI\2026-11-14-12-09-34"
```

### 步骤 3（推荐）：创建虚拟环境

```bat
python -m venv .venv
.venv\Scripts\activate
```

激活成功后命令行前会出现 `(.venv)`。

### 步骤 4：安装依赖（仅自动化模块需要）

```bat
pip install -r requirements.txt
python -m playwright install chromium
```

- 只做"分析 / 生成 / 校验 / 报告 / 导出"**可以不装**，核心功能零依赖。
- 若公司网络受限，可加国内镜像：`pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple`
- `playwright install chromium` 需下载约 150 MB 浏览器，只需执行一次。
- **不想下载浏览器**：Windows 10/11 自带 Edge，可直接复用——运行时加 `--channel msedge`（或用 `--channel chrome` 复用已装 Chrome），跳过 `playwright install` 这一步。

### 步骤 5：启动

```bat
python main.py
```

或双击 `run.bat`。启动后进入菜单：

```
  1) 输入样例并分析格式（更换样例可重新分析）
  2) 查看 / 保存 / 加载 规则配置
  3) 校验兑换码（单个或批量）
  4) 按规则生成离线测试码
  5) 生成统计报告（长度分布 / 字符频率 / 匹配率）
  6) 启动本地模拟兑换页面
  7) 自动化测试（Playwright，本地环境）
  0) 退出
```

### 步骤 6：完整走一遍（推荐顺序）

1. 选 `1` → 输入方式选 `2`（文件）→ 路径填 `samples\sample_codes.txt` → 规则名 `demo` → 保存规则 `y`。
2. 选 `4` → 数量 `20` → 种子留空 → 导出 TXT `y`（默认写 `out\codes.txt`）。
3. 选 `5` → 数据来源 `3` → 数量 `200` → 导出报告 `y`。
4. 选 `6` → 端口 `8765` → 有效码清单填 `out\codes.txt`，然后在浏览器打开 `http://127.0.0.1:8765/` 手工点几下。
5. 选 `7` → 地址默认 `http://127.0.0.1:8765/` → 测试码文件 `out\codes.txt` → 最大次数 `20` → 间隔 `0.5` → 非 dry-run → 回车开始。

运行中可按 **p 暂停 / r 继续 / q 停止**（Ctrl+C 亦可）。

### 常见问题

| 现象 | 原因与处理 |
|---|---|
| 中文显示为乱码 | 程序已把控制台切到 UTF-8；若仍异常，先执行 `chcp 65001` |
| `ModuleNotFoundError: playwright` | 未装依赖；执行步骤 4，或改用 `--dry-run` |
| `playwright` 提示缺少浏览器 | 执行 `python -m playwright install chromium`；或改用 `--channel msedge` 复用本机 Edge |
| 下载 Chromium 太慢/被拦截 | 不下载浏览器，运行时加 `--channel msedge`（或 `--channel chrome`） |
| 页面打不开 / 端口占用 | 换端口：`python main.py mock --port 8790` |
| 生成失败"规则空间不足" | 规则描述的组合数小于请求数量，减少数量或补充样例 |
| 保存的规则加载报错 | 规则文件被手工改坏；重新用样例分析生成 |

---

## 四、手机运行方案

先给结论：**Python 程序跑在电脑上，手机不需要安装任何东西**。手机能承担的角色有三种，按需要选一个：

| 方案 | 手机的角色 | 前置条件 | 适合场景 |
|---|---|---|---|
| **A. 手机浏览器直连**（推荐） | 打开电脑上的模拟兑换页面，手工输入/粘贴测试码 | 手机与电脑同一 Wi-Fi + 防火墙放行 | 想在真实手机上体验页面交互、验证触屏布局 |
| **B. 电脑模拟手机**（零真机） | 不参与；电脑用 Playwright 以 iPhone/Pixel 视口跑自动化 | 无（只要 Playwright） | 只想验证手机端布局与自动化流程 |
| **C. 真机 Chrome 远程调试** | 手机浏览器被电脑驱动，自动填码、点提交、读结果 | USB 线 + adb + Chrome 命令行开关 | 必须在真实手机浏览器里跑自动化 |

### 方案 A：手机浏览器直连（最常用）

1. 电脑上以"局域网模式"启动模拟页面：

```bat
python main.py mock --host 0.0.0.0 --port 8790 --issued out\codes.txt
```

启动后会打印两个地址：

```
本地模拟兑换页面已启动：
  http://127.0.0.1:8790/   (本机)
  http://10.172.188.246:8790/   <- 手机浏览器打开这个地址
```

2. 手机连**同一个 Wi-Fi**，浏览器打开第二个地址（`http://<电脑IP>:8790/`）。

3. 如果手机打不开，99% 是 Windows 防火墙拦截。以管理员身份打开 PowerShell 执行一次：

```bat
netsh advfirewall firewall add rule name="couponfmt-mock" dir=in action=allow protocol=TCP localport=8790
```

删除规则：`netsh advfirewall firewall delete rule name="couponfmt-mock"`。

排查顺序：`ipconfig` 确认电脑 IPv4 地址 → 手机浏览器试 `http://<IP>:8790/` → 确认手机没开"访客网络/AP 隔离"（公司或酒店 Wi-Fi 常开，会隔离设备）→ 用手机流量测试会失败，必须关掉移动数据、只连 Wi-Fi。

页面已做移动端适配（`viewport` + 16px 输入框防 iOS 自动放大 + 小屏内边距收敛），手机竖屏下布局完整、无横向滚动。

> 注意：`--host 0.0.0.0` 会把模拟页面暴露给整个局域网。它只是个本地假页面、不含任何真实数据，但用完请按 Ctrl+C 关闭。

### 方案 B：电脑模拟手机（不需要真机）

Playwright 内置了几十种设备参数（视口、UA、DPR、触屏），直接指定设备名即可，不必连手机：

```bat
:: 模拟 iPhone 13 跑自动化
python main.py autotest --codes out\codes.txt --device "iPhone 13" --channel msedge

:: 模拟 Pixel 5
python main.py autotest --codes out\codes.txt --device "Pixel 5" --channel msedge
```

常见设备名：`iPhone 13`、`iPhone 14 Pro`、`iPhone SE`、`Pixel 5`、`Galaxy S9+`、`iPad Mini`。
设备名写错时程序会列出相近候选，不会静默失败。

实测（390×664 视口，iPhone 13 参数）：

```
 目标地址      : http://127.0.0.1:8790/
 驱动          : playwright
 连接/设备     : 设备模拟 Pixel 5
 格式正确      : 2    不存在: 1    格式错误: 1
```

### 方案 C：真机 Chrome 远程调试（USB）

Playwright 不能直接驱动手机，但可以通过 CDP 接管手机上的 Chrome。步骤：

1. 手机开启「开发者选项 → USB 调试」，用数据线连电脑，`adb devices` 能看到设备。
2. 手机 Chrome 打开 `chrome://flags/#enable-command-line-on-non-rooted-devices`，设为 Enabled，重启 Chrome。
3. 建立调试端口转发：

```bat
adb shell "echo '_ --remote-debugging-port=9222' > /data/local/tmp/chrome-command-line"
adb forward tcp:9222 localabstract:chrome_devtools_remote
```

4. 电脑上先以局域网模式起模拟页面（手机要能访问到）：

```bat
python main.py mock --host 0.0.0.0 --port 8790 --issued out\codes.txt
```

5. 用 CDP 接管手机浏览器跑自动化（`--url` 填**手机能访问的局域网地址**）：

```bat
python main.py autotest --url http://10.172.188.246:8790/ --cdp http://127.0.0.1:9222 --codes out\codes.txt --max 10
```

说明：`--cdp` 模式下 `--device` 会被忽略（真机参数由手机自己决定）；CDP 地址同样只允许本机/局域网。

### 方案 D：直接在手机上跑 Python（了解即可，不推荐）

- **Android**：装 Termux，`pkg install python`，把项目拷进去，`python main.py` 可运行分析/生成/校验/报告等**纯标准库**功能。但 Playwright 在 Termux 上不可用（没有桌面浏览器与依赖），自动化模块无法运行。
- **iOS**：没有可用的 Python 运行环境（Pythonista/a-Shell 受沙箱限制，无法安装 Playwright），**不支持**。

因此推荐组合是：**电脑跑工具 + 手机看页面（方案 A）**，需要自动跑手机视口就用方案 B。

---

## 五、命令行用法（非交互）

```bat
:: 分析样例并保存规则
python main.py analyze --file samples\sample_codes.txt --name demo --save rules\demo.json

:: 查看规则摘要 / 列举全部规则
python main.py rules show --rule rules\demo.json
python main.py rules list

:: 生成 50 个测试码并导出
python main.py gen --rule rules\demo.json --count 50 --seed 2026 --out out\codes.txt --csv out\codes.csv

:: 校验（退出码 0=全部通过，1=存在不通过）
python main.py validate --rule rules\demo.json --file samples\mixed_test.txt
python main.py validate --rule rules\demo.json --codes "SAVE-2026-9A8B" --strict

:: 统计报告
python main.py report --rule rules\demo.json --count 200 --txt out\report.txt --csv out\stats.csv

:: 本地模拟页面（默认仅本机；--host 0.0.0.0 允许手机访问）
python main.py mock --port 8765 --issued out\codes.txt
python main.py mock --host 0.0.0.0 --port 8790 --issued out\codes.txt

:: 自动化测试（默认只允许本机/局域网地址）
python main.py autotest --codes out\codes.txt --max 20 --delay 0.5
python main.py autotest --codes out\codes.txt --max 20 --channel msedge
python main.py autotest --codes out\codes.txt --max 10 --device "iPhone 13" --channel msedge
python main.py autotest --url http://10.172.188.246:8790/ --cdp http://127.0.0.1:9222 --max 10
python main.py autotest --codes out\codes.txt --max 10 --dry-run
```

`autotest` 的防护：

- 只允许 `localhost / 127.0.0.1 / ::1`、**局域网私有地址**（`10.x` / `172.16-31.x` / `192.168.x`）与 `file://`；公网地址必须显式 `--allow-remote`（前提是你已获得该测试环境授权）。
- `--max` 限制总次数；连续 5 次异常/超时自动终止；`--stop-on-success` 命中即停。
- 每条结果实时落盘，中途停止也不会丢数据。
- `--channel msedge` 复用本机 Edge（也可 `--channel chrome`），免去下载 Chromium；不指定则使用 Playwright 自带 Chromium。
- `--device` 用 Playwright 内置设备参数模拟手机；`--cdp` 接管真机/已开远程调试的浏览器。

---

## 六、测试样例与预期输出

### 6.1 样例文件

| 文件 | 内容特征 |
|---|---|
| `samples/sample_codes.txt` | 6 条 `SAVE-2026-XXXX`，定长、双分隔符 |
| `samples/luhn_codes.txt` | 6 条 12 位数字，末位 Luhn 校验 |
| `samples/variable_codes.txt` | 5 条 `GC-` + 3~5 位变长段 |
| `samples/mixed_test.txt` | 8 条混合串（2 条合规、6 条各类错误） |
| `samples/autotest_mixed.txt` | 7 条自动化测试清单（命中 / 不存在 / 格式错误三种返回都会出现） |

### 6.2 `analyze`（定长样例）

```bat
python main.py analyze --file samples\sample_codes.txt --name save_demo --save rules\save_demo.json
```

```
规则名称      : save_demo
分析模式      : 定长逐位分析
样本数量      : 6
长度范围      : 14
前缀          : 'SAVE'
后缀          : ''
分隔符        : '-'
可变字段字符集: 234789BFHKMNPQRTWXZ
正则(通用)    : ^SAVE\-2026\-[A-Z0-9]{4}$
正则(严格)    : ^SAVE\-2026\-[234789BFHKMNPQRTWXZ]{4}$
校验位        : 未检出
规则置信度    : 94.0%
字段拆分      :
   1. 固定 'SAVE'
   2. 分隔符 '-' ×1
   3. 固定 '2026'
   4. 分隔符 '-' ×1
   5. 大写字母+数字 ×4 [A-Z0-9]
```

### 6.3 `analyze`（校验位识别）

```bat
python main.py analyze --file samples\luhn_codes.txt --name luhn_demo --save rules\luhn_demo.json
```

```
正则(通用)    : ^\d{12}$
校验位        : Luhn (MOD 10)（末 1 位，置信度 100.00%）
字段拆分      :
   1. 纯数字 ×12 \d
```

### 6.4 `gen`（生成并自校验）

```bat
python main.py gen --rule rules\save_demo.json --count 5 --seed 42
```

```
SAVE-2026-71HF
SAVE-2026-E86Y
SAVE-2026-5R21
SAVE-2026-5DEW
SAVE-2026-1ZCY
```

### 6.5 `validate`（逐段定位错误）

```bat
python main.py validate --rule rules\save_demo.json --file samples\mixed_test.txt
```

```
SAVE-2026-8F3K               PASS
SAVE-2026-QW7M               PASS
SAVE-2026-8F3                FAIL 长度 13 不在规则范围 14~14 内; 不匹配正则 ^SAVE\-2026\-[A-Z0-9]{4}$; 第 5 段长度不足：需要 4~4 位，实际仅剩 3 位
SAVE-2025-8F3K               FAIL 不匹配正则 ^SAVE\-2026\-[A-Z0-9]{4}$; 第 3 段应为固定文本 '2026'，实际为 '2025'
save-2026-8f3k               FAIL 不匹配正则 ^SAVE\-2026\-[A-Z0-9]{4}$; 第 1 段应为固定文本 'SAVE'，实际为 'save'
SAVE_2026_8F3K               FAIL 不匹配正则 ^SAVE\-2026\-[A-Z0-9]{4}$; 第 2 段应为固定文本 '-'，实际为 '_'
SAVE-2026-8F3KX              FAIL 长度 15 不在规则范围 14~14 内; 不匹配正则 ^SAVE\-2026\-[A-Z0-9]{4}$; 末尾存在多余字符 'X'
XSAVE-2026-8F3K              FAIL 长度 15 不在规则范围 14~14 内; 不匹配正则 ^SAVE\-2026\-[A-Z0-9]{4}$; 第 1 段应为固定文本 'SAVE'，实际为 'XSAV'
------------------------------------------------------------
通过率 25.00%（2/8）
```

### 6.6 `report`（节选）

```
【2】长度分布
  长度    数量    占比     直方图
  14      200     100.0%   ########################################

【5】格式匹配率
  通用模式匹配率  : 100.00%
  严格模式匹配率  : 6.00%
  校验位通过率    : 100.00%
  规则正则        : ^SAVE\-2026\-[A-Z0-9]{4}$
  规则空间        : 1,679,616 种组合（约 20.68 bit 熵）
  说明            : 严格模式只接受样本中出现过的字符，因此按字符集全集生成的测试码在严格模式下匹配率天然偏低，属正常现象。
```

> 严格模式只接受样本中出现过的字符，因此按字符集全集生成的测试码在严格模式下匹配率天然偏低，报告里会给出这条说明。

### 6.7 自动化测试（`--dry-run` 演练）

```
[  1] SAVE-2026-7KWW           格式错误      10ms  [dry-run] 格式错误
[  2] SAVE-2026-6EZQ           格式错误      10ms  [dry-run] 格式错误
[  3] SAVE-2026-ZVSF           格式正确      10ms  [dry-run] 格式正确
...
 执行次数      : 8 / 计划 30
 格式正确      : 3
 不存在        : 2
 格式错误      : 3
 停止原因      : 达到最大测试次数 8
 CSV 报告      : out\autotest_report.csv
```

CSV 字段：`seq, timestamp, code, status, status_label, response_text, elapsed_ms, note`。

### 6.8 自动化测试（真实浏览器，复用本机 Edge）

```bat
python main.py mock --port 8788 --issued out\codes.txt
python main.py autotest --url http://127.0.0.1:8788/ --codes samples\autotest_mixed.txt --max 10 --channel msedge
```

```
[  1] SAVE-2026-7KWW           格式正确     125ms  格式正确　（SAVE-2026-7KWW）
[  2] SAVE-2026-ZZZZ           不存在       20ms  不存在　（SAVE-2026-ZZZZ）
[  3] BAD-CODE-1234            格式错误      23ms  格式错误　（BAD-CODE-1234）
[  4] SAVE-2026-6EZQ           格式正确      22ms  格式正确　（SAVE-2026-6EZQ）
[  5] SAVE-2026-QQQQ           不存在       21ms  不存在　（SAVE-2026-QQQQ）
[  6] SAVE-2026-8F3            格式错误      22ms  格式错误　（SAVE-2026-8F3）
[  7] SAVE-2026-ZVSF           格式正确      21ms  格式正确　（SAVE-2026-ZVSF）

 执行次数      : 7 / 计划 7
 格式正确      : 3
 不存在        : 2
 格式错误      : 2
 停止原因      : 测试码已全部执行完毕
```

对应的 `out\autotest_mixed.csv`（节选）：

```
seq,timestamp,code,status,status_label,response_text,elapsed_ms,note
1,2026-11-14 12:30:33.520,SAVE-2026-7KWW,ok,格式正确,格式正确（SAVE-2026-7KWW）,125,命中格式正确，已记录
2,2026-11-14 12:30:33.741,SAVE-2026-ZZZZ,not_found,不存在,不存在（SAVE-2026-ZZZZ）,20,自动切换下一个测试码
3,2026-11-14 12:30:33.965,BAD-CODE-1234,format_error,格式错误,格式错误（BAD-CODE-1234）,23,自动切换下一个测试码
```

`--stop-on-success` 时只执行 1 次即停止，停止原因显示"命中「格式正确」并按配置停止"。

---

## 七、自检结果

```bat
python -m unittest discover -s tests -t .
```

```
Ran 79 tests in 1.606s
OK
```

覆盖：校验位算法与识别（含公开测试向量）、三条归纳路径、规则 JSON 往返、生成与去重、校验与严格模式、报告与导出、模拟服务三分支、运行器限次与远程地址拦截、暂停/继续/停止控制逻辑、局域网绑定与私有地址白名单、设备模拟参数传递。

端到端验证（本机实测）：

- 模拟服务 `GET /` 返回页面（含 `#code / #submit / #result`），`POST /api/redeem` 三种返回均正确；
- **真实浏览器链路已跑通**：Playwright 1.65 + 本机 Edge（`--channel msedge`），7 条混合清单得到 3 通过 / 2 不存在 / 2 格式错误，CSV 逐条落盘，`--stop-on-success` 行为正确；
- **手机链路已跑通**：`--host 0.0.0.0` 后本机地址与局域网地址（`http://10.172.188.246:8790/`）均返回 200 与正确接口结果；`--device "Pixel 5"` 以 390×664 视口跑完自动化并截图确认移动端布局无溢出；
- `main.py` 各子命令输出与退出码符合预期（`validate` 全通过返回 0，否则 1）；
- 交互菜单在管道输入下可正常跑完"分析 → 校验 → 生成 → 报告 → 规则查看"，输入结束能优雅退出。

---

## 八、已知限制

1. **小样本不确定**：2 个样例时"恒定字符"可能只是巧合；建议 5 个以上样例，且覆盖不同取值。
2. **无分隔符的复合字段**：若格式本身是"4 位数字 + 4 位字母"且无分隔符，样本较少时会被合并成 `[A-Z0-9]{8}`。补充更多样本（≥10）后引擎会在强证据处自动切分。
3. **私有校验位**：只覆盖公开算法；未检出不等于没有校验位。
4. **严格模式的语义**：严格模式等于"只接受观测字符"，对未见字符会判失败，这是设计取舍，不是缺陷。
5. **浏览器自动化依赖页面结构**：默认按 `#code / #submit / #result` 及常见回退选择器查找元素，页面结构差异大时需用 `--selector-*` 指定。
6. **手机直连受网络环境限制**：公司/酒店 Wi-Fi 常启用 AP 隔离，会阻断手机访问电脑；需换家用路由或开热点（热点下手机访问电脑的网关地址）。
7. **真机 CDP 需要额外开关**：Chrome for Android 默认不监听调试端口，必须开启 `enable-command-line-on-non-rooted-devices`；iOS 上无法用此方案（Safari 不提供 CDP）。
8. **手机不能跑自动化**：Termux 里的 Python 只能跑纯标准库功能，Playwright 无法在 Android/iOS 上安装；自动化必须留在电脑。

---

## 九、声明

本工具用于**本地离线**的格式研究与程序自测。所有生成、校验、统计、自动化操作均在本机完成或仅针对本机模拟页面；不连接真实兑换平台，不进行批量猜测或提交。格式匹配不等于兑换码有效。
