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

---

# Cover Art —— 海报级封面

Cover Art 会出现在**应用推荐位和应用列表页**，是这个应用给人的第一眼。
现在那两张（logo 居中放在渐变底上）是能用的占位，但没有吸引力，该换。

需要两张：
- **16:9**，宽 ≥978px（建议 1920×1080）
- **1:1**，宽 ≥978px（建议 1080×1080）

## 构图上的两条硬要求

**给标题留白。** 商店会在封面上叠加应用名和按钮，通常压在左侧或下方。
所以主体**不要居中占满**——16:9 让主体偏右三分之一，左边留出干净的暗部；
1:1 让主体偏上，下方留白。这是这类横幅最容易翻车的地方。

**仍然不能有文字，不能有头显。** 理由同上面详情图那节：模型写不对汉字，
商店禁止出现其他设备品牌。

## 主推方案：时间的纵深

一叠玻璃卡片朝画面深处退去，越远的卡片越暗、越模糊，像一条望不到底的时间走廊。
最前一张被一束暖光照亮——「今天」被从过去里拎出来。冷蓝的纵深 + 一点暖光的对比，
是这张海报的全部张力所在。

**16:9 prompt**（附 `ref-stack-large.png`）

```
A long receding stack of translucent frosted-glass cards floating in dark
empty space, arranged in perspective so they recede diagonally into the
distance toward the upper right, the far cards dissolving into cool blue haze.
The nearest card is lit by a single warm golden light from the left, its
frosted surface glowing, edges catching a bright rim highlight. All card
surfaces are completely blank. Deep indigo to near-black gradient background,
volumetric light, subtle dust motes in the light beam, cinematic, shallow
depth of field. Composition: cards occupy the right two thirds, the left
third is dark and empty negative space.
Aspect ratio 16:9, 1920x1080, premium 3D product render, high detail.
Negative: no text, no letters, no Chinese characters, no watermark, no VR
headset, no person, no device branding, no clutter.
```

**1:1 prompt**（同参考图，改构图）

```
A stack of translucent frosted-glass cards floating in dark empty space,
receding into depth toward the upper area of the frame, far cards dissolving
into cool blue haze. The nearest card is lit by a single warm golden light
from the left, edges catching a bright rim highlight. All surfaces blank.
Deep indigo to near-black gradient, volumetric light, cinematic.
Composition: cards in the upper two thirds, lower third dark and empty.
Aspect ratio 1:1, 1080x1080, premium 3D product render, high detail.
Negative: no text, no letters, no Chinese characters, no watermark, no VR
headset, no person, no device branding.
```

## 备选方案 A：时间的扇面

卡片不再叠成一摞，而是像摊开的一副牌横扫过画面，从左下扬到右上。
每张卡片透出极淡的、不同年代的质感（老照片的颗粒、泛黄的纸、蓝图的网格），
但都虚化到只剩色调，看不出具体内容。

```
A wide sweeping fan of translucent frosted-glass cards arcing across the
frame from lower left to upper right, like a dealer spreading a deck in
mid air. Each card carries an extremely faint different tint — warm sepia,
pale amber, cool cyan, soft grey — suggesting different eras, but all far too
blurred and abstract to read any content. Surfaces otherwise blank. Soft
studio lighting with a warm key from the left, deep indigo background,
gentle motion blur at the trailing end of the arc.
Aspect ratio 16:9, 1920x1080, premium 3D render, cinematic, high detail.
Negative: no text, no letters, no Chinese characters, no photographs, no
recognizable imagery on the cards, no watermark, no VR headset, no person.
```

比主推方案更活泼，但「不同年代」这层意思很容易被模型理解成往卡上贴图片，
出图后要盯住卡面是不是真的干净。

## 备选方案 B：房间里的一束光

偏生活场景：清晨的房间，一叠卡片悬在窗边的光里，尘埃在光柱中浮动。
主体小、环境大，卖的是「它就安静地待在你的空间里」这个感觉。

```
A softly lit modern bedroom at early morning, warm sunlight slanting through
a window on the left. A small stack of translucent frosted-glass cards floats
in mid air within the beam of light, glowing gently, dust motes drifting
around them. The cards are blank. The room is calm, minimal, neutral tones,
out of focus in the background. The floating cards are the only sharp element.
Composition: cards in the right half, the left half is the light-filled
window and empty wall.
Aspect ratio 16:9, 1920x1080, cinematic photography look, shallow depth of
field, high detail.
Negative: no text, no letters, no Chinese characters, no people, no VR
headset, no device branding, no clutter.
```

这张和详情图的房间实拍呼应，但比实拍好看得多。风险是环境抢戏、主体太小，
生成时可以把 "small stack" 改成 "prominent stack" 调整比重。

## 出图之后

想加那个「今」字的话，**不要让 AI 写**，用 `ref-logo-cutout.png` 里那张已经修好笔画的
卡片抠图叠上去。那个字是我用系统字体按卡面透视重绘贴回去的，笔画是对的。

上传位置：Metadata → Assets → Cover Art，两个格子分别对应 16:9 和 1:1。
上传后**记得点 Assets 区块的 Save**——不保存的话刷新页面就没了，这个坑我踩过。
