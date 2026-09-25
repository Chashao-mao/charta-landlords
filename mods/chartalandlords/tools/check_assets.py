#!/usr/bin/env python3
"""资源自检：把生成出来的资源文件解码回来，跟设计逐像素/逐项对拍。

`gen_assets.py` 负责画，这个脚本负责**证明确实画进去了**：Charta 的 `.mccard/.mcsuit`
是「调色板索引 + gzip」的私有格式，编码时任何一个位偏移错了，游戏里看到的就是一张花牌，
而单纯看文件大小是发现不了的。发布前跑一次，一条命令覆盖：

  1. `back.mccard` / `blank.mcsuit` 解码后与设计图**逐像素一致**（含 alpha 档位）；
  2. 两副牌堆 JSON 的张数（54 / 108）、卡面条目重复数（两副牌必须是「每张各 2 份」）、
     以及每张牌的 `suit/rank/texture/translation` 四个字段齐全；
  3. 选桌图标 / 模组 logo / Modrinth 图标的尺寸，以及三张图确实是同一套美术
     （把大图用最近邻缩到 70×70 后与选桌图标比差异像素比例）。

用法：python tools/check_assets.py      （退出码非 0 = 有检查没过）
"""

import gzip
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_assets  # noqa: E402  （要复用同一份调色板与同一支画笔）

ROOT = gen_assets.ROOT
RES = gen_assets.RES

FAILURES = []


def check(condition, message):
    if condition:
        print("  ok   " + message)
    else:
        print("  FAIL " + message)
        FAILURES.append(message)


def decode_card_image(path, width, height):
    """按 Charta 的 CardImage 格式解码：gzip 后的 width*height 个字节，每字节 (alpha << 6) | color。"""
    with gzip.open(path, "rb") as handle:
        data = handle.read()
    assert len(data) == width * height, "{}: expected {} bytes, got {}".format(
        path, width * height, len(data))
    image = Image.new("RGBA", (width, height))
    pixels = []
    for value in data:
        alpha_index = (value >> 6) & 0x03
        color_index = value & 0x3F
        rgb = gen_assets.COLOR_PALETTE[color_index]
        # 与 CardImage.getARGBPixel 完全一致：有颜色但 alpha=0 的像素按不透明处理
        alpha = 255 if (color_index != 0 and alpha_index == 0) else gen_assets.ALPHA_PALETTE[alpha_index]
        pixels.append(((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha))
    image.putdata(pixels)
    return image


def compare_pixels(name, actual, expected):
    if actual.size != expected.size:
        check(False, "{}: size {} != design {}".format(name, actual.size, expected.size))
        return
    a = actual.convert("RGBA").tobytes()
    b = expected.convert("RGBA").tobytes()
    diff = sum(1 for x, y in zip(a, b) if x != y)
    check(diff == 0, "{}: {}×{} 与设计逐像素一致（差异 {} 字节）".format(
        name, actual.size[0], actual.size[1], diff))


def check_images():
    print("images (.mccard / .mcsuit)")
    back_path = os.path.join(RES, "data", "chartalandlords", "images", "deck", "back.mccard")
    suit_path = os.path.join(RES, "data", "chartalandlords", "images", "suit", "blank.mcsuit")
    compare_pixels("deck/back.mccard", decode_card_image(back_path, 25, 35), gen_assets.card_back())
    compare_pixels("suit/blank.mcsuit", decode_card_image(suit_path, 13, 13), gen_assets.blank_suit())


def check_decks():
    print("decks (JSON)")
    for name, expected in (("doudizhu", 54), ("doudizhu_double", 108)):
        path = os.path.join(RES, "data", "chartalandlords", "decks", name + ".json")
        with open(path, "r", encoding="utf-8") as handle:
            deck = json.load(handle)
        cards = deck.get("cards", [])
        check(len(cards) == expected, "{}: {} 张".format(name, len(cards)))
        check(len(deck.get("suits", [])) == 5, "{}: 5 个花色（含 Joker 的无花色）".format(name))
        incomplete = [c for c in cards
                      if not all(k in c.get("card", {}).get("components", {})
                                 for k in ("charta:suit", "charta:rank", "charta:flipped"))
                      or "texture" not in c or "translation" not in c]
        check(not incomplete, "{}: 每张牌的 suit/rank/flipped/texture/translation 都齐全".format(name))
        keys = [(c["card"]["components"]["charta:suit"], c["card"]["components"]["charta:rank"])
                for c in cards]
        if expected == 54:
            check(len(set(keys)) == 54, "{}: 54 张互不重复".format(name))
        else:
            counts = {}
            for key in keys:
                counts[key] = counts.get(key, 0) + 1
            check(all(count == 2 for count in counts.values()) and len(counts) == 54,
                  "{}: 正好是 54 种牌各 2 张".format(name))


def check_icons():
    print("icons")
    game = Image.open(os.path.join(RES, "assets", "chartalandlords", "textures",
                                   "gui", "game", "doudizhu.png")).convert("RGBA")
    logo = Image.open(os.path.join(RES, "chartalandlords_logo.png")).convert("RGBA")
    project = Image.open(os.path.join(RES, "icon.png")).convert("RGBA")
    check(game.size == (70, 70), "选桌图标 70×70（当前 {}）".format(game.size))
    check(logo.size == (140, 140), "模组列表 logo 140×140（当前 {}）".format(logo.size))
    check(project.size == (700, 700), "项目图标 icon.png 700×700（当前 {}）".format(project.size))
    check(project.width >= 256 and project.height >= 256,
          "Modrinth 要求项目图标不小于 256×256")
    # 项目图标是「透明底 + 大牌 + 名字标」的另一张图（对齐 Charta 的做法），不是选桌图标的放大版：
    # 四角必须是透明的，否则上传到项目页会带上一块底色。
    corners = [project.getpixel((0, 0)), project.getpixel((699, 0)),
               project.getpixel((0, 699)), project.getpixel((699, 699))]
    check(all(pixel[3] == 0 for pixel in corners),
          "项目图标四角透明（当前 alpha {}）".format([pixel[3] for pixel in corners]))
    opaque = sum(1 for pixel in project.getdata() if pixel[3] > 0) / float(700 * 700)
    check(0.10 < opaque < 0.90, "项目图标既不空也不满（不透明像素占比 {:.1%}）".format(opaque))
    # 模组列表 logo 与选桌图标是同一套美术：大图最近邻缩回 70×70 后差异应当极小
    scaled = logo.resize((70, 70), Image.NEAREST).tobytes()
    base = game.tobytes()
    diff = sum(1 for x, y in zip(base, scaled) if x != y) / float(len(base))
    check(diff < 0.02, "模组 logo 与选桌图标是同一套美术（像素差异 {:.2%}）".format(diff))


def check_pack_meta():
    """资源包元数据：模板在 src/main/templates（`${mod_name}` 由 gradle 展开），产物在 jar 根目录。"""
    print("pack metadata")
    template = os.path.join(ROOT, "src", "main", "templates", "pack.mcmeta")
    check(os.path.exists(template), "src/main/templates/pack.mcmeta 存在（对齐 Charta 的资源包元数据）")
    if os.path.exists(template):
        with open(template, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        pack = data.get("pack", {})
        check(isinstance(pack.get("pack_format"), int), "pack_format 是整数（1.21.1 = 34）")
        check(pack.get("description") == "${mod_name}",
              "description 用占位符交给 gradle 展开（当前 " + str(pack.get("description")) + "）")
    # 展开后的产物（跑过一次构建才会有）：确认占位符真的被替换成了模组名
    generated = os.path.join(ROOT, "build", "generated", "sources", "modMetadata", "pack.mcmeta")
    if os.path.exists(generated):
        with open(generated, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        description = data.get("pack", {}).get("description")
        check(description == "ChartaLandlords",
              "展开后的 description 是模组名（当前 " + str(description) + "）")
    else:
        print("  skip 展开后的 pack.mcmeta（还没构建过）")


def main():
    check_images()
    check_decks()
    check_icons()
    check_pack_meta()
    print("")
    if FAILURES:
        print("ASSET CHECK FAILED: {} 项没过".format(len(FAILURES)))
        for failure in FAILURES:
            print("  - " + failure)
        return 1
    print("ASSET CHECK OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
