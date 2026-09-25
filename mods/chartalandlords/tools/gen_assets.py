#!/usr/bin/env python3
"""生成斗地主模组的数据包与资源文件。

产物：
  src/main/resources/data/chartalandlords/decks/doudizhu.json          54 张（3 人局）
  src/main/resources/data/chartalandlords/decks/doudizhu_double.json   108 张两副牌（4 人局）
  src/main/resources/data/chartalandlords/images/deck/back.mccard      牌背
  src/main/resources/data/chartalandlords/images/suit/blank.mcsuit     Joker 花色图标
  src/main/resources/data/chartalandlords/structure/empty.nbt          GameTest 空结构
  src/main/resources/assets/chartalandlords/textures/gui/game/doudizhu.png  选桌图标（70×70）
  src/main/resources/chartalandlords_logo.png                         模组列表 logo（140×140，
                                                               与选桌图标同一套美术，
                                                               对应 neoforge.mods.toml 的 logoFile）
  src/main/resources/icon.png                                          仓库 / 项目页图标（700×700，
                                                                透明底 + 大牌 + 名字标，位置对齐
                                                                Charta 的 common/src/main/resources/icon.png；
                                                                Modrinth / CurseForge 的项目图标就用它，
                                                                `gradlew dist` 会复制成 dist/charta-landlords-icon.png；
Charta 的 .mccard/.mcsuit 是「调色板索引 + gzip」的私有格式：
  25x35（牌）或 13x13（花色）字节，每字节 = (alphaIndex << 6) | colorIndex，
  整块 gzip 压缩。调色板与透明度档位必须与 Charta 的 CardImage 完全一致。

用法：python tools/gen_assets.py
"""

import gzip
import json
import math
import os
import struct

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")

# 与 dev.lucaargolo.charta.common.utils.CardImage 完全一致的调色板
COLOR_PALETTE = [
    0x000000, 0x252525, 0x494949, 0x6E6E6E, 0x929292, 0xB7B7B7, 0xDCDCDC, 0xFFFFFF,
    0x7F0000, 0xB20000, 0xE30000, 0xFF0000, 0xFF5353, 0xFF7575, 0xFF9898, 0xFFBABA,
    0x7F3F00, 0xB25800, 0xE37000, 0xFF7F00, 0xFFA953, 0xFFBA75, 0xFFCB98, 0xFFDCBA,
    0x7F7F00, 0xB2B200, 0xE3E300, 0xFFFF00, 0xFFFF53, 0xFFFF75, 0xFFFF98, 0xFFFFBA,
    0x007F00, 0x00B200, 0x00E300, 0x00FF00, 0x53FF53, 0x75FF75, 0x98FF98, 0xBAFFBA,
    0x007F7F, 0x00B2B2, 0x00E3E3, 0x00FFFF, 0x53FFFF, 0x75FFFF, 0x98FFFF, 0xBAFFFF,
    0x00007F, 0x0000B2, 0x0000E3, 0x0000FF, 0x5353FF, 0x7575FF, 0x9898FF, 0xBABAFF,
    0x7F007F, 0xB200B2, 0xE300E3, 0xFF00FF, 0xFF53FF, 0xFF75FF, 0xFF98FF, 0xFFBAFF,
]
ALPHA_PALETTE = [0, 85, 170, 255]

DARK_RED = (0x7F, 0x00, 0x00)
RED = (0xB2, 0x00, 0x00)
GOLD = (0xE3, 0xE3, 0x00)
BRIGHT_GOLD = (0xFF, 0xFF, 0x00)
WHITE = (0xFF, 0xFF, 0xFF)


def nearest_color_index(rgb):
    best_index, best_distance = 0, None
    r, g, b = rgb
    for index, value in enumerate(COLOR_PALETTE):
        pr, pg, pb = (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF
        distance = (r - pr) ** 2 + (g - pg) ** 2 + (b - pb) ** 2
        if best_distance is None or distance < best_distance:
            best_index, best_distance = index, distance
    return best_index


def encode_image(image, path):
    """把 PIL 图像编码成 Charta 的 .mccard/.mcsuit 格式。"""
    width, height = image.size
    pixels = bytearray()
    for y in range(height):
        for x in range(width):
            r, g, b, a = image.getpixel((x, y))
            if a < 32:
                color_index, alpha_index = 0, 0
            else:
                color_index = nearest_color_index((r, g, b))
                if a < 128:
                    alpha_index = 1
                elif a < 213:
                    alpha_index = 2
                else:
                    alpha_index = 3
            pixels.append(((alpha_index & 0x03) << 6) | (color_index & 0x3F))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(gzip.compress(bytes(pixels), mtime=0))


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


# --------------------------------------------------------------------- 牌堆数据

STANDARD_SUITS = ["spades", "hearts", "clubs", "diamonds"]
STANDARD_RANKS = ["ace", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                  "jack", "queen", "king"]


def standard_card(suit, rank):
    return {
        "card": {
            "components": {
                "charta:suit": "charta:" + suit,
                "charta:rank": "charta:" + rank,
                "charta:flipped": False,
            }
        },
        "texture": "charta:standard/{}_{}".format(suit, rank),
        "translation": "card.charta.{}.{}".format(suit, rank),
    }


def joker_card(rank_path, texture, translation):
    return {
        "card": {
            "components": {
                "charta:suit": "charta:blank",
                "charta:rank": "chartalandlords:" + rank_path,
                "charta:flipped": False,
            }
        },
        "texture": texture,
        "translation": translation,
    }


def base_cards():
    cards = [standard_card(suit, rank) for suit in STANDARD_SUITS for rank in STANDARD_RANKS]
    # 小王：黑色 Joker 面；大王：红色 Joker 面（都复用 Charta 标准牌面素材）
    cards.append(joker_card("small_joker", "charta:standard/spades_joker", "card.chartalandlords.joker.small"))
    cards.append(joker_card("big_joker", "charta:standard/hearts_joker", "card.chartalandlords.joker.big"))
    return cards


def deck_suits():
    suits = [
        {"suit": "charta:" + suit, "texture": "charta:standard/" + suit,
         "translation": "suit.charta." + suit}
        for suit in STANDARD_SUITS
    ]
    suits.append({"suit": "charta:blank", "texture": "chartalandlords:blank",
                  "translation": "suit.chartalandlords.blank"})
    return suits


def build_decks():
    single = {
        "translation": "deck.chartalandlords.doudizhu",
        "suits": deck_suits(),
        "cards": base_cards(),
        "rarity": "common",
        "tradeable": False,
        "texture": "chartalandlords:back",
    }
    double = {
        "translation": "deck.chartalandlords.doudizhu_double",
        "suits": deck_suits(),
        "cards": base_cards() * 2,
        "rarity": "uncommon",
        "tradeable": False,
        "texture": "chartalandlords:back",
    }
    write_json(os.path.join(RES, "data", "chartalandlords", "decks", "doudizhu.json"), single)
    write_json(os.path.join(RES, "data", "chartalandlords", "decks", "doudizhu_double.json"), double)
    print("decks: 54 张 / 108 张 已生成")


# --------------------------------------------------------------------- 图片资源

def rect(draw, box, fill):
    draw.rectangle(box, fill=fill)


def card_back():
    """牌背：奶油描边 + 金线 + 暗红底 + 细菱格 + 中央金冠徽章。

    旧版是「满屏斜格纹 + 中央菱形」：25×35 上斜线太密，远看是一团橙色噪点，四角也没有收边。
    现在把金色收进「描边、四角、中央徽章」三处，底纹只比底色亮一档（`RED` on `DARK_RED`），
    于是远看仍然是干净的一张红牌，凑近看才有菱格与王冠。绘制无随机数，可重复。
    """
    image = Image.new("RGBA", (25, 35), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # 外圈：奶油白描边 → 金线 → 暗红底（三层各 1px，牌面边界在任何底色上都看得清）
    rect(draw, (0, 0, 24, 34), WHITE)
    rect(draw, (1, 1, 23, 33), GOLD)
    rect(draw, (2, 2, 22, 32), DARK_RED)
    # 细菱格底纹：斜向每隔 4px 点一像素，只比底色亮一档，不抢中央徽章
    for y in range(3, 32):
        for x in range(3, 22):
            if (x + y) % 4 == 0:
                rect(draw, (x, y, x, y), RED)
    # 四角金点：让牌背在扇形里也能看出「每张牌」的边界
    for corner in ((4, 5), (20, 5), (4, 29), (20, 29)):
        rect(draw, (corner[0], corner[1], corner[0], corner[1]), BRIGHT_GOLD)
    # 中央徽章：金菱形 → 暗红内芯 → 金冠（与模组图标同一个冠）
    draw.polygon([(12, 8), (21, 17), (12, 26), (3, 17)], fill=GOLD)
    draw.polygon([(12, 11), (18, 17), (12, 23), (6, 17)], fill=DARK_RED)
    for row, line in enumerate(CROWN_9X5):
        for col, char in enumerate(line):
            if char == "#":
                rect(draw, (8 + col, 14 + row, 8 + col, 14 + row), BRIGHT_GOLD)
    return image


def blank_suit():
    image = Image.new("RGBA", (13, 13), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # 小皇冠，作为大小王的「花色」标记
    draw.polygon([(2, 8), (2, 4), (4, 6), (6, 3), (8, 6), (10, 4), (10, 8)], fill=BRIGHT_GOLD)
    draw.rectangle((2, 9, 10, 10), fill=GOLD)
    return image


# --------------------------------------------------------------------- 像素字形与图标

# 5×7 点阵字形。只收录图标真正用到的字符（牌面 2 / 3 / A 与标题 DOUDIZHU）；
# 想加别的点数字形，在这里补一条 7 行 × 5 列的字符串即可。
FONT_5X7 = {
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
}
FONT_WIDTH = 5
FONT_HEIGHT = 7
FONT_SPACING = 1

# 7×7 花色点阵
PIPS_7X7 = {
    "spade": [
        "...#...",
        "..###..",
        ".#####.",
        "#######",
        ".#####.",
        "...#...",
        "..###..",
    ],
    "heart": [
        ".##.##.",
        "#######",
        "#######",
        "#######",
        ".#####.",
        "..###..",
        "...#...",
    ],
    "club": [
        "..###..",
        ".#####.",
        "..###..",
        "##.#.##",
        "#######",
        "...#...",
        "..###..",
    ],
    "diamond": [
        "...#...",
        "..###..",
        ".#####.",
        "#######",
        ".#####.",
        "..###..",
        "...#...",
    ],
}

# 9×5 金冠（填在标题与牌扇之间的空档里）
CROWN_9X5 = [
    "#...#...#",
    "#.#.#.#.#",
    "#########",
    "#########",
    ".#######.",
]


def lerp_color(start, end, t):
    """两色线性插值（t = 0..1）——用来把毡面画成多段渐变而不是几块硬色。"""
    return tuple(int(round(start[i] + (end[i] - start[i]) * t)) for i in range(4))


def text_width(value):
    if not value:
        return 0
    return len(value) * FONT_WIDTH + (len(value) - 1) * FONT_SPACING


def glyph_art(char):
    """字形 → 点阵画（"#" = 涂色）；未收录的字符画成空白，方便定位拼写错误。"""
    glyph = FONT_5X7.get(char)
    if glyph is None:
        return []
    return [line.replace("1", "#").replace("0", ".") for line in glyph]


class Painter:
    """把 70×70 的「设计网格」映射到实际输出尺寸。

    一个设计像素永远映射成**整块**矩形（而不是靠图像缩放插值），所以
    70×70 的选桌图标与 140×140 的模组 logo 都是干净的整数倍像素——
    140 恰好是 2 倍，细到 1px 的字形笔画不会被插值糊掉。
    绘制过程中没有随机数，输出可重复。
    """

    UNITS = 70

    def __init__(self, size):
        self.size = size
        self.scale = size / float(self.UNITS)
        self.image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.image)

    def box(self, x0, y0, x1, y1, color):
        """闭区间的单位矩形（含 x1 / y1）。"""
        left = int(round(x0 * self.scale))
        top = int(round(y0 * self.scale))
        right = max(left, int(round((x1 + 1) * self.scale)) - 1)
        bottom = max(top, int(round((y1 + 1) * self.scale)) - 1)
        self.draw.rectangle((left, top, right, bottom), fill=color)

    def art(self, rows, x, y, color):
        """画一段点阵（"#" = 涂色，"." = 留空）。"""
        for row, line in enumerate(rows):
            for col, bit in enumerate(line):
                if bit == "#":
                    self.box(x + col, y + row, x + col, y + row, color)

    def text(self, value, x, y, color, shadow=None):
        """画 5×7 点阵文本；给了 shadow 就先偏移一格画一遍阴影。"""
        if shadow is not None:
            self.text(value, x + 1, y + 1, shadow)
        for index, char in enumerate(value):
            self.art(glyph_art(char), x + index * (FONT_WIDTH + FONT_SPACING), y, color)

    def text_corner(self, value, x, y, color):
        """牌面右下角的第二个角标。

        真实扑克这一角是旋转 180° 的（因为真人会把牌转过来看），但在**扇形牌列**里
        每张牌都是正立的，画成倒置只会让人以为画错了，所以这里画正立的角标。
        它的作用是：被右边的牌压住时，露出来的右侧窄条里仍然能读出点数。
        """
        self.text(value, x, y, color)

    def text_corner(self, value, x, y, color):
        """牌面右下角的第二个角标。

        真实扑克这一角是旋转 180° 的（因为真人会把牌转过来看），但在**扇形牌列**里
        每张牌都是正立的，画成倒置只会让人以为画错了，所以这里画正立的角标。
        它的作用是：被右边的牌压住时，露出来的右侧窄条里仍然能读出点数。
        """
        self.text(value, x, y, color)

    def star(self, cx, cy, outer, color, inner_ratio=0.42):
        """五角星：外/内顶点交替，画出来是硬边（PIL 的多边形填充不做抗锯齿）。"""
        points = []
        for index in range(10):
            angle = -math.pi / 2 + index * math.pi / 5
            radius = outer if index % 2 == 0 else outer * inner_ratio
            points.append(((cx + radius * math.cos(angle)) * self.scale,
                           (cy + radius * math.sin(angle)) * self.scale))
        self.draw.polygon(points, fill=color)


# 图标的配色：中国红毡面 + 双金边，牌面用暖白，和牌局界面的「内凹面板」暗描边同一套语言。
ICON_RAIL_DARK = (0x1E, 0x08, 0x0A, 0xFF)
ICON_RAIL_GOLD = (0xE0, 0xB4, 0x32, 0xFF)
ICON_FELT_HI = (0xC2, 0x16, 0x1C, 0xFF)
ICON_FELT_TOP = (0xA6, 0x10, 0x18, 0xFF)
ICON_FELT_MID = (0x8C, 0x0C, 0x14, 0xFF)
ICON_FELT_LOW = (0x70, 0x08, 0x10, 0xFF)
# 毡面自上而下的色标：四段插值比两段硬切更像被顶光照着的台布
ICON_FELT_RAMP = (ICON_FELT_HI, ICON_FELT_TOP, ICON_FELT_MID, ICON_FELT_LOW)
ICON_CARD_FACE = (0xFA, 0xF5, 0xEA, 0xFF)
ICON_CARD_EDGE = (0x2A, 0x16, 0x14, 0xFF)
ICON_CARD_EDGE_RED = (0xD8, 0x18, 0x20, 0xFF)
ICON_INK = (0x1C, 0x1C, 0x26, 0xFF)
ICON_INK_RED = (0xC0, 0x12, 0x1A, 0xFF)
ICON_GOLD = (0xF0, 0xC0, 0x2A, 0xFF)
ICON_GOLD_HI = (0xFF, 0xE6, 0x8A, 0xFF)
ICON_SHADOW = (0x44, 0x04, 0x0A, 0xFF)


def felt_color(t):
    """在 {@link ICON_FELT_RAMP} 的四个色标之间按 t（0..1）插值。"""
    stops = len(ICON_FELT_RAMP) - 1
    position = max(0.0, min(1.0, t)) * stops
    index = min(stops - 1, int(position))
    return lerp_color(ICON_FELT_RAMP[index], ICON_FELT_RAMP[index + 1], position - index)


def game_icon(size=70):
    """斗地主选桌图标 / 模组 logo：中国红毡面 + 双金边 + 「DOUDIZHU」标题 + 五张牌扇。

    设计意图（与 Charta 自带的 `crazy_eights` / `solitaire` 图标同一套视觉语言：
    **满幅彩色底 + 游戏名点阵字 + 一排牌面**，但用红金配色一眼区分开）：

    * 牌扇从左到右是 `♠3 / 小王 / 大王 / ♠2 / ♥A`——「大小王 + 2」正是斗地主最标志性的牌；
    * 大王那张画在最上层、抬高且描红边，中间一颗大金星，是整张图的视觉焦点；
    * 标题下面垫一个金冠，填掉标题与牌扇之间的空档，也点出「地主」。
    """
    p = Painter(size)
    # 外框：暗描边 → 双金边 → 细暗描边（金边里加一道暗线，才有「金属压边」的层次）
    p.box(0, 0, 69, 69, ICON_RAIL_DARK)
    p.box(1, 1, 68, 68, ICON_RAIL_GOLD)
    p.box(2, 2, 67, 67, ICON_RAIL_GOLD)
    p.box(3, 3, 66, 66, ICON_RAIL_DARK)
    # 毡面：自上而下多段渐变 + 顶部一道高光（4 段硬切会在牌后面留下明显的横条）
    steps = 9
    span = 65 - 4 + 1
    for index in range(steps):
        y0 = 4 + span * index // steps
        y1 = 4 + span * (index + 1) // steps - 1
        p.box(4, y0, 65, y1, felt_color(index / (steps - 1)))
    p.box(5, 5, 64, 5, (0xE6, 0x44, 0x4A, 0xFF))

    # 标题：金色阴影 + 暖白字，居中
    title = "DOUDIZHU"
    title_x = 4 + (62 - text_width(title)) // 2
    p.text(title, title_x, 8, ICON_CARD_FACE, shadow=ICON_SHADOW)

    # 金冠：先垫一块暗底当作描边，否则金色在红毡上会糊掉
    p.box(29, 18, 39, 24, ICON_SHADOW)
    p.art(CROWN_9X5, 30, 19, ICON_GOLD)
    p.box(31, 21, 37, 21, ICON_INK_RED)

    def card(x, y, edge, face):
        p.box(x, y, x + 17, y + 25, edge)
        p.box(x + 1, y + 1, x + 16, y + 24, face)

    def number_card(x, y, label, suit):
        card(x, y, ICON_CARD_EDGE, ICON_CARD_FACE)
        ink = ICON_INK_RED if suit == "heart" else ICON_INK
        p.text(label, x + 2, y + 2, ink)
        p.art(PIPS_7X7[suit], x + 2, y + 10, ink)
        # 右下角第二个角标：牌被右边的牌压住时，露出来的就是它
        p.text_corner(label, x + 11, y + 17, ink)

    def joker_card(x, y, big):
        card(x, y, ICON_CARD_EDGE_RED if big else ICON_CARD_EDGE, ICON_CARD_FACE)
        ink = ICON_INK_RED if big else ICON_INK
        p.star(x + 5.5, y + 6.0, 3.4, ink)
        # 中央大星：只有整张可见的大王才画；被压住的牌画了只会变成噪点
        if big:
            p.star(x + 9.5, y + 15.0, 6.2, ICON_GOLD_HI)
            p.star(x + 9.5, y + 15.0, 5.0, ICON_INK_RED)
        else:
            # 小王只露出左边一条，窄条里再补一颗星，免得看上去像空白牌
            p.star(x + 5.0, y + 19.5, 3.4, ink)

    # 画序：离中心越近的牌越晚画（也就压在邻居上面），最后画的大王完整可见。
    # 这样每张牌要么露出左上角标、要么露出右下角标，五张牌的点数全都能读出来。
    number_card(8, 36, "3", "spade")
    number_card(44, 36, "A", "heart")
    joker_card(17, 33, False)
    number_card(35, 33, "2", "spade")
    joker_card(26, 30, True)
    return p.image


def mod_icon(size=700):
    """仓库 / 项目页图标（Modrinth、CurseForge、仓库预览）：一张大牌 + 横压的名字标。

    和 Charta 自己的 `common/src/main/resources/icon.png` 同一个路数：**透明底、一张大牌当主角、
    模组名以「红字 + 深色描边」横压在牌面上**——作为扩展模组，和本体并排摆着时一眼是一家人。
    区别只有内容：那张牌上的主角是斗地主的「大王金星」（金色描边 + 红色内芯，和游戏图标里的大王
    同一套画法），底下的金冠也是同一个冠。

    尺寸 700×700 = 设计网格 70 的 **10 倍**（Charta 用 660，不是网格倍数；这里优先保证每个设计像素
    都落成干净的 10×10 方块）。
    """
    p = Painter(size)

    # 主角：一张竖牌（比游戏图标里的牌更大），暖白牌面 + 深色描边 + 内圈金线
    p.box(20, 6, 49, 46, ICON_CARD_EDGE)
    p.box(21, 7, 48, 45, ICON_CARD_FACE)
    p.box(23, 9, 46, 43, ICON_GOLD)
    p.box(24, 10, 45, 42, ICON_CARD_FACE)
    # 牌面中央的大王金星：先画金色外圈，再压红色内芯（和 game_icon 里大王那张同一套画法）
    p.star(35, 18, 7.5, ICON_GOLD_HI)
    p.star(35, 18, 6.0, ICON_INK_RED)
    # 名字标：一条深红横条压在牌面中间，金字/暖白字写在条上。
    # 比「给字母描边」干净得多——缩到 64px 时也不会糊成一团黑边，而且和界面里的标题条是同一套语言。
    p.box(9, 28, 60, 38, ICON_SHADOW)
    p.box(9, 29, 60, 37, ICON_FELT_MID)
    p.box(9, 29, 60, 29, ICON_GOLD)
    p.box(9, 37, 60, 37, ICON_GOLD)
    title = "DOUDIZHU"
    p.text(title, (70 - text_width(title)) // 2, 30, ICON_CARD_FACE, shadow=ICON_SHADOW)
    # 底下的金冠：2 倍点阵，先垫一层暗底当描边（金色直接画在透明底上会糊）
    p.box(25, 51, 44, 63, ICON_SHADOW)
    for row, line in enumerate(CROWN_9X5):
        for col, bit in enumerate(line):
            if bit == "#":
                p.box(26 + col * 2, 52 + row * 2, 27 + col * 2, 53 + row * 2, ICON_GOLD)
    p.box(30, 56, 40, 57, ICON_INK_RED)
    return p.image


# --------------------------------------------------------------------- GameTest 空结构
def nbt_string(name, value):
    payload = value.encode("utf-8")
    return b"\x08" + nbt_name(name) + struct.pack(">H", len(payload)) + payload


def nbt_int(name, value):
    return b"\x03" + nbt_name(name) + struct.pack(">i", value)


def nbt_list(name, element_type, payloads):
    return (b"\x09" + nbt_name(name) + bytes([element_type])
            + struct.pack(">i", len(payloads)) + b"".join(payloads))


def nbt_compound(name, body):
    return b"\x0a" + nbt_name(name) + body + b"\x00"


def nbt_compound_payload(body):
    """列表元素只写负载：compound 负载 = 各字段 + TAG_End（不写 tag id / 名字）。"""
    return body + b"\x00"


def nbt_name(name):
    payload = name.encode("utf-8")
    return struct.pack(">H", len(payload)) + payload


def empty_structure(size=3):
    air = nbt_compound_payload(nbt_string("Name", "minecraft:air"))
    body = (
        nbt_int("DataVersion", 3955)
        + nbt_list("size", 3, [struct.pack(">i", size)] * 3)
        + nbt_list("palette", 10, [air])
        + nbt_list("blocks", 10, [])
        + nbt_list("entities", 10, [])
    )
    payload = gzip.compress(nbt_compound("", body), mtime=0)
    path = os.path.join(RES, "data", "chartalandlords", "structure", "empty.nbt")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(payload)
    print("gametest structure: empty.nbt 已生成")


def main():
    build_decks()

    encode_image(card_back(), os.path.join(RES, "data", "chartalandlords", "images", "deck", "back.mccard"))
    encode_image(blank_suit(), os.path.join(RES, "data", "chartalandlords", "images", "suit", "blank.mcsuit"))
    print("images: back.mccard / blank.mcsuit 已生成")

    icon = game_icon()
    icon_path = os.path.join(RES, "assets", "chartalandlords", "textures", "gui", "game", "doudizhu.png")
    os.makedirs(os.path.dirname(icon_path), exist_ok=True)
    icon.save(icon_path)

    # 模组列表 logo：neoforge.mods.toml 的 logoFile 指向 jar 根目录下的 chartalandlords_logo.png。
    # 用 140×140 而不是 128：140 恰好是设计网格（70）的 2 倍，笔画是干净的整数倍像素；
    # 128 会让 1px 的字形/星芒笔画在 1px 与 2px 之间跳变。模组列表会等比缩放，尺寸无硬性要求。
    logo = game_icon(140)
    logo_path = os.path.join(RES, "chartalandlords_logo.png")
    logo.save(logo_path)

    # 仓库 / 项目页图标（Modrinth、CurseForge、仓库预览）：放在资源根目录，和 Charta 的
    # `common/src/main/resources/icon.png` 同一个位置、同一个路数（透明底 + 大牌 + 名字标）。
    # 700 = 设计网格 70 的 10 倍，每个设计像素都是干净的 10×10 方块。
    mod_icon_path = os.path.join(RES, "icon.png")
    mod_icon(700).save(mod_icon_path)

    preview_dir = os.path.join(ROOT, "build", "_assets_preview")
    os.makedirs(preview_dir, exist_ok=True)
    icon.resize((280, 280), Image.NEAREST).save(os.path.join(preview_dir, "game_icon.png"))
    logo.resize((280, 280), Image.NEAREST).save(os.path.join(preview_dir, "mod_logo.png"))
    mod_icon(350).save(os.path.join(preview_dir, "mod_icon.png"))
    card_back().resize((250, 350), Image.NEAREST).save(os.path.join(preview_dir, "deck_back.png"))
    blank_suit().resize((260, 260), Image.NEAREST).save(os.path.join(preview_dir, "blank_suit.png"))
    print("gui: game/doudizhu.png（70×70）、chartalandlords_logo.png（140×140）、"
          "icon.png（700×700）已生成（预览在 build/_assets_preview）")

    empty_structure()


if __name__ == "__main__":
    main()
