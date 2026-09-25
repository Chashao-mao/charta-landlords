# libs/ —— 编译期依赖（第三方二进制）

`charta-neoforge-1.21.1-1.2.5.jar` 是 **Charta 1.2.5** 的官方发行文件，本模组用它做
`compileOnly`（不打包进本模组的 jar），开发运行期再复制到 `run/mods` 与 `run-gametest/mods`
供 FML 当模组加载（见 `build.gradle` 的 `syncDevMods` / `syncGameTestMods`）。

| 项 | 值 |
| --- | --- |
| 来源 | Modrinth 项目 **charta**（`sFamPxlk`），版本 1.2.5（版本 id `cuOxTn8V`） |
| 直链 | `https://cdn.modrinth.com/data/sFamPxlk/versions/cuOxTn8V/charta-neoforge-1.21.1-1.2.5.jar` |
| 许可 | **MPL-2.0**（全文见同目录 `charta-LICENSE.txt`），作者 Luca Argolo |
| 大小 / 校验 | 7,440,585 字节；SHA-1 `8a86e06ff70316499d9d7ea6239b5ae645a733e9` |

这份文件被**提交进仓库**，是为了让新克隆的仓库直接 `gradlew build` 就能编译、CI 也不用额外步骤。
如果你想把它移出 git（仓库更干净），可以：删掉这条提交、把 `libs/` 加进 `.gitignore`，
然后在 `build.gradle` 里改成从 Modrinth 的 Maven 取依赖
（`maven { url 'https://api.modrinth.com/maven' }` + `compileOnly "maven.modrinth:charta:1.2.5"`），
并把 `syncDevMods` / `syncGameTestMods` 的来源改成解析出来的文件——代价是构建/开发运行都需要联网。

> Charta 是 MPL-2.0：允许再分发，但必须随附许可文本（已放在本目录）并保留版权与来源说明。
> 本模组自身的代码是 MIT（见仓库根的 `LICENSE`）。
