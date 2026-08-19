# 商店详情图 —— AI 生成用 Prompt 与参考图

商店要求详情图 **4–7 张，16:9，宽度 ≥1680px**（建议直接出 1920×1080）。

现状：已上传 5 张模拟器实拍。按你的决定**保留 3 张**（`detail-01/02/03.png`），
其余 **至少再补 1 张、建议补 3 张** 由 AI 生成，凑够 4–7 张。

---

## 先看这条，不然出来的图不能用

**不要让 AI 生成卡片上的中文。** 图像模型渲染汉字几乎必错笔画——这个应用的 logo
就是这么翻车的，那个「今」字第三笔画错，是我后来用系统字体重绘贴回去才修好的。

所以下面所有 prompt 都要求**卡片表面留空**。需要文字的话，生成完之后用设计工具
另贴一层真实文本，或者干脆不加。

另外商店明确规定：**素材中不得出现其他设备品牌的名称、图标或图片**
（原文：Assets will be displayed in PICO Store, Please do not show names, icons or images of other device brands）。
所以**不要画头显、不要画戴头显的人**。

---

## 参考图

都在 `docs/store/ai-refs/` 下：

| 文件 | 用途 | 怎么用 |
| --- | --- | --- |
| `ref-stack-large.png` | **主参考**。卡片堆叠的形态、材质、倾斜角度、品牌底色 | 每个 prompt 都附上 |
| `ref-logo-cutout.png` | 卡片堆叠的纯净抠图（透明背景，1536²） | 需要精确形态时附上 |
| `ref-screenshot.png` | 应用在真实房间里的样子，给 AI 一个空间关系参考 | 场景类 prompt 附上 |
| `ref-brand-color.png` | 品牌底色渐变 #3B4E7A → #1B2338 | 需要统一色调时附上 |

---

## 通用风格描述（三个 prompt 共用，保证成组）

```
Style: premium 3D product render, soft studio lighting, frosted translucent
glass material with subtle blue-white tint, gentle rim light along every edge,
shallow depth of field, clean and calm, no clutter.
Palette: cool indigo background (#3B4E7A to #1B2338 gradient), frosted white
glass objects, no saturated accent colors.
Aspect ratio 16:9, 1920x1080, high detail.
Negative: no text, no letters, no Chinese characters, no watermark, no logo,
no VR headset, no person wearing a headset, no device branding, no harsh
shadows, no dark black background.
```

---

## Prompt 1 —— 主视觉（英雄图）

附参考图：`ref-stack-large.png`

```
A stack of six translucent frosted-glass cards floating in mid air, seen from
a slight three-quarter angle, tilted gently to the left with each card behind
offset slightly to the right, about one centimeter apart, like a fanned deck
of wallet cards. The front card faces the viewer and its surface is completely
blank. Soft light passes through the glass, edges catch a bright rim light.
Background is a smooth cool indigo gradient, empty and calm.
```

中文意图：把图标那个「一叠卡片」的概念放大成主视觉。**卡面必须空白。**

## Prompt 2 —— 翻页动作

附参考图：`ref-stack-large.png`

```
The same stack of translucent frosted-glass cards floating in mid air. The
front card is caught mid-motion: it has shrunk to about half size and drifted
upward, fading out into soft light, while the card behind it moves forward to
take its place. Subtle motion blur and a faint light trail follow the
departing card. All card surfaces are blank. Cool indigo gradient background.
```

中文意图：表现「点一下，前面那张缩小上移消散，后面顶上来」这个交互。
这正是应用里的实际行为，别画成向左滑出——早期版本是那样，后来改掉了。

## Prompt 3 —— 材质特写

附参考图：`ref-logo-cutout.png`

```
Extreme close-up of two overlapping translucent frosted-glass panels with
softly rounded corners, filling the frame diagonally. The glass is thick and
milky with a faint blue tint; light refracts through the edges creating a
bright thin highlight. Surfaces are completely blank. Shallow depth of field,
the far panel softly out of focus. Cool indigo gradient background.
```

中文意图：把毛玻璃材质放大，作为一张纯质感图，和另外两张形成节奏。

---

## 拿到图之后

1. 确认尺寸 ≥1680px 宽、16:9。不够就放大或重出，别拉伸变形。
2. 存成 PNG 放进 `docs/store/`，命名 `detail-04.png` 起。
3. 单张控制在 2MB 以内（后台单次上传总量有 10MB 限制）。
4. 上传位置：开发者后台 → Metadata → Assets → Detail Images。
   同时把现有 5 张里多余的 2 张删掉，保留 3 张实拍。

如果生成出来卡面上还是冒出了文字或汉字，直接重出——审核里出现错字比少一张图麻烦得多。
