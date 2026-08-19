# 上架信息 —— 历史上的今天

- Item ID：`7674957638396067876`
- Package Name：`tech.illusion.todayinhistory`
- 组织：科斯莫西（深圳市科斯莫西科技有限公司）
- 后台入口：开发者后台 → 应用 → **Submission → Metadata**（老版控制台）
  Metadata 与「版本/发行渠道」是分开的，**不需要先建版本就能填**。
- 当前 Metadata 草稿：version 1.0，状态 Draft

---

## 已填入后台并保存

| 区块 | 状态 | 内容 |
| --- | --- | --- |
| Description | ✅ 已保存 | 见下方文案 |
| Specification | ✅ 已保存 | App / News + Entertainment / Single Player / 6-DOF Controller / Sitting + Standing / 简体中文 / 无内购 / China |
| Publisher Info | ✅ 已保存 | 科斯莫西（随组织带出）、hello@story.app；Term of Service 与 Website 留空 |
| Assets | ⚠️ 已上传未保存 | 封面 16:9 与 1:1 已传；详情图已传 5 张；**图标那格反复上传不进去** |
| Policy compliance | ❌ 待你提供 | 隐私政策 URL（必填）、授权文件 |
| Content Rating | ❌ 未保存 | 中国大陆无需填问卷，只需点 Save，但按钮当前无响应 |
| Pricing | ❌ 未保存 | Chinese Price 下拉当前无响应 |

后三项很可能是被上面未完成的 Assets / Policy compliance 卡着，等那两块补齐后应该就能点了。

### 文案（已入库）

**App Name**：历史上的今天

**Brief**（64/200）：
> 一叠卡片浮在身边，随手翻看今天这一天历史上发生过什么。366 天共 54,018 条记录随应用一同安装，完全离线，不联网也能用。

**Description**（575/8000）：
> 历史上的今天，就摆在你面前。
>
> 打开应用，一叠半透明的卡片浮在你身边，像随手摊开的一沓卡片。最前面那张写着今天这一天历史上发生过的一件事——某一年的这一天，谁出生了，谁离开了，什么事情就此改变。
>
> 点一下，最前面的卡片缩小消散，后面的一张顶上来。不点也没关系，每五分钟它自己翻一页。右下角的面板一直显示着此刻的时间、日期和星期，跨过零点会自动换到新的一天。
>
> 条目按年份顺序排列，但每次重新打开都会从随机的位置开始，所以你不会总是读到同样的开头。三类条目用一个小色点区分：大事记、出生、逝世。
>
> 【功能】
> · 366 天完整数据，共 54,018 条记录，随应用一同安装
> · 完全离线，运行时不发起任何网络请求，不采集任何数据
> · 卡片堆叠浏览，点击翻页，五分钟自动翻页
> · 每次冷启动随机起始位置，年份顺序不变
> · 时间 / 日期 / 星期一体面板，跨零点自动切换
> · 无额外权限申请，无内购
>
> 【数据来源】
> 本应用的条目文本来自中文维基百科，采用 CC BY-SA 4.0 授权（https://creativecommons.org/licenses/by-sa/4.0/），经开源项目 LuoH-AN/TodayInHistory（MIT 授权，https://github.com/LuoH-AN/TodayInHistory）整理后使用。

## 素材

| 用途 | 文件 | 规格 | 状态 |
| --- | --- | --- | --- |
| 图标（商店） | `icon_store_1024.png` | 1024²，RGB 满幅 | 上传不进去，需手动传 |
| 封面 16:9 | `cover-16x9.png` | 1920×1080 | ✅ 已上传 |
| 封面 1:1 | `cover-1x1.png` | 1080² | ✅ 已上传 |
| 详情图 | `detail-01/02/03.png` | 1920×1080 | ✅ 已上传（后台还多 2 张，需删） |
| 详情图（待补） | 见 `ai-image-prompts.md` | 1920×1080 | AI 生成，至少再补 1 张 |
| 图标分层（分发用） | `icon_bg.png` / `icon_fg2.png` | 1024²，内切圆内无透明像素 | 备用 |

图标由 `tools/build_icon_assets.py` 生成，可复现。

---

## 需要你处理

### 1. 隐私政策 URL（必填，卡着 Policy compliance）

应用不联网、不采集任何数据，但后台仍要求填一个可访问的地址。
后台在该处提供了中文模板下载链接（Chinese Template Privacy Policy.pdf），照着改一份挂到任意可访问地址即可。

### 2. 授权文件（Authorization document）

后台原文：应提供版权方的正式文件（如授权函），声明你有权在应用中使用该内容。
这一项对应的正是本应用引用的维基百科文本。该字段没标必填，但内容确实来自第三方，
建议准备一份说明：内容取自中文维基百科，依 CC BY-SA 4.0 授权使用，已在应用商店介绍
与开源仓库 README 中完成署名。

### 3. 详情图补齐

见 `ai-image-prompts.md`。现有 3 张实拍 + 至少 1 张 AI 生成，凑够 4 张下限。
同时把后台里多余的 2 张删掉。

### 4. 定价

Chinese Price 必填。本应用无内购，建议选免费。
注意后台提示：**上架后价格 30 天内不能再改**。

### 5. 签名的 release 包

见下节。

### 6. 软著登记号

中国大陆渠道通常要求，需你提供。

---

## 签名 release 包的做法

目前只有 debug 包，`app/build.gradle.kts` 里没有签名配置。步骤：

**第一步：生成 keystore**（只需做一次，务必备份，丢了就无法再发布更新）

```bash
keytool -genkeypair -v \
  -keystore ~/keystores/todayinhistory.jks \
  -alias todayinhistory \
  -keyalg RSA -keysize 2048 -validity 10000
```

会依次问你密码、姓名、组织等。密码记牢。

**第二步：把密码放进 `local.properties`**（该文件已在 `.gitignore` 里，不会进仓库）

```properties
RELEASE_STORE_FILE=/Users/yuqiaozhu/keystores/todayinhistory.jks
RELEASE_STORE_PASSWORD=你的密码
RELEASE_KEY_ALIAS=todayinhistory
RELEASE_KEY_PASSWORD=你的密码
```

**第三步：在 `app/build.gradle.kts` 里加签名配置**

```kotlin
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            val path = localProps.getProperty("RELEASE_STORE_FILE")
            if (path != null) {
                storeFile = file(path)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}
```

`isMinifyEnabled = false` 是保守起见：Spatial SDK 依赖反射与 ECS 注册，开启混淆需要
额外的 keep 规则并重新验证，首次上架不建议一起动。

**第四步：打包并验证签名**

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

打完包务必装到设备上跑一遍再提交——release 构建与 debug 的行为可能不同。

---

## 关于发行渠道（与 Metadata 无关，但上架最终要过）

新版控制台 Create Version 的设备列表里：

- **PICO 4 Ultra 及更早机型**：4 Ultra 要求 `minSdkVersion ≤ 34`，4 / 4 Pro / Neo3 要求 `= 29`
- **Project Swan**：Swan 标注 **Coming soon，选项禁用**

而 PICO Spatial SDK 要求 `minSdk ≥ 35`
（`spatial-sdk_port-android-apps.md:37`「minSdk: Upgrade to version 35 or above」，本工程为 35）。

两者互斥：Spatial 应用投不到 4 Ultra 及更早机型，唯一能承载的 Swan 渠道尚未开放。
**Metadata 可以照常填完，但版本创建要等 Swan 开放。**
