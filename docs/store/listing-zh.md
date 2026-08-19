# 上架信息草稿 —— 历史上的今天

状态：**草稿，未提交**。PICO 开发者后台当前无法为本应用创建版本，原因见文末「阻塞项」。
本文件是把能定的内容先定下来，等渠道开放后可直接粘贴。

- Item ID：`7674957638396067876`
- Package Name：`tech.illusion.todayinhistory`
- 组织：科斯莫西（深圳市科斯莫西科技有限公司）
- 应用形态：Spatial App / Volumetric WindowContainer（Volume）

---

## 基本信息

| 字段 | 内容 |
| --- | --- |
| 应用名称 | 历史上的今天 |
| 一句话简介 | 一叠卡片浮在身边，随手翻看今天这一天发生过什么 |
| 版本号 | 1.0（versionCode 1） |
| 建议分类 | 教育 / 效率（后台实际可选项待确认） |
| 语言 | 简体中文 |
| 联网 | 无。全部数据随安装包分发，运行时不发起任何网络请求 |
| 内购 | 无 |
| 权限 | 无额外权限申请 |

## 详细介绍

> 历史上的今天，就摆在你面前。
>
> 打开应用，一叠半透明的卡片浮在你身边，像随手摊开的一沓卡片。最前面那张写着今天这一天历史上发生过的一件事——某一年的这一天，谁出生了，谁离开了，什么事情就此改变。
>
> 点一下，最前面的卡片缩小消散，后面的一张顶上来。不点也没关系，每五分钟它自己翻一页。右下角的面板一直显示着此刻的时间、日期和星期。
>
> 全部 366 天、54,018 条记录随应用一同安装，不联网也能用。条目按年份顺序排列，但每次重新打开都会从随机的位置开始，所以你不会总是读到同样的开头。
>
> 三类条目用一个小色点区分：大事记、出生、逝世。

**功能要点**

- 366 天完整数据，共 54,018 条，随包分发，零网络请求
- 卡片堆叠式浏览，点击翻页，五分钟自动翻页
- 每次冷启动随机起始位置，年份顺序不变
- 时间 / 日期 / 星期一体面板
- 跨零点自动切换到新的一天

**数据来源**

本应用的条目文本来自中文维基百科，采用 CC BY-SA 4.0 授权，
经开源项目 LuoH-AN/TodayInHistory（MIT）整理后使用。

## 素材

| 用途 | 文件 | 规格 |
| --- | --- | --- |
| 图标背景层 | `docs/store/icon_bg.png` | 1024×1024，内切圆内无透明像素 ✓ |
| 图标第二层 | `docs/store/icon_fg2.png` | 1024×1024 |
| 平铺图标 | `docs/store/icon_flat_1024.png` | 1024×1024 |
| 截图 | `docs/store/screenshot-01-stack.png` | 2160×2158，模拟器实机 |
| 截图（裁切） | `docs/store/screenshot-01-crop.png` | 1560×960 |

图标由 `tools/build_icon_assets.py` 从 `docs/logo-source-cutout.png` 生成，可复现。
分层规范见 `spatial-design_foundation_icon_app-icon-and-layered-design`：高光与阴影由系统添加，素材内不得自带。

---

## 需要你确认或提供的

以下几项我无法代填，都需要你的决定或公司信息：

1. **年龄分级问卷** —— 逐题作答，属于法律声明。
2. **隐私政策 URL** —— 应用不联网、不收集任何数据，但后台通常仍要求填写地址。
3. **应用分类** —— 后台实际可选项要等能创建版本后才看得到。
4. **著作权/软著登记号** —— 中国大陆渠道通常要求。
5. **更好的截图** —— 现有截图是模拟器视角，应用在画面里偏小、卧室背景占了大半。真机上换个视角重拍会好很多。

## 待办（代码侧）

1. ~~数据来源标注~~ —— 已处理。按决定不做应用内「关于」页，改为写在仓库 `README.md`
   的「数据来源与授权」一节，商店介绍里也已加入「数据来源」段落。

2. **签名的 release 包**
   目前只有 debug 包，`app/build.gradle.kts` 未配置签名。上架需要签名的 release APK。

3. **`locales_config.xml` 只声明了 `en-US`**
   而界面文案全是中文。不影响运行，但与实际语言不符，建议改为 `zh-CN`。

---

## 阻塞项：现在无法创建版本

后台「Create Version → Select Release Channel」里，Supported Devices 只有两组：

- **Consumer Store - PICO 4 Ultra and earlier models**
  - PICO 4 / PICO 4 Pro / PICO Neo3：要求 `minSdkVersion` = 29
  - PICO 4 Ultra：要求 `minSdkVersion` **≤ 34**
- **Consumer Store - Project Swan**
  - Swan：标注 **Coming soon，选项为禁用状态**

而 PICO Spatial SDK 要求 `minSdk` **≥ 35**
（`spatial-sdk_port-android-apps.md:37`「**minSdk**: Upgrade to version 35 or above」，本工程为 35）。

两个条件互斥：Spatial 应用无法投放到 PICO 4 Ultra 及更早机型，而唯一能承载它的
Project Swan 渠道尚未开放。因此当前**任何设备都选不了，版本创建流程走不完**。

本应用尚无任何版本，overview 只有空状态，没有分区里的「+ Create Version」占位入口
（账号内已有 Swan 草稿的 Spatial 应用是通过那个占位建的）。Version Management 页同样是空状态、同一个对话框。

**结论**：等 Project Swan 渠道开放后再来创建版本；届时本文件的内容可直接使用。
