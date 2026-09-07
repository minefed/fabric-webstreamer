# Web Streamer
A mod to display live streams in-game.

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/webstreamer?style=flat-square&label=Modrinth%20Downloads)](https://modrinth.com/mod/webstreamer)
[![CurseForge Downloads](https://img.shields.io/curseforge/dt/628932?style=flat-square&label=CurseForge%20Downloads)](https://www.curseforge.com/minecraft/mc-mods/webstreamer)

## Web Display block
This mod adds a single item -for now-, called "Web Display". It's a resizable and directional block that allows you to 
display [HLS] video streams but also static images. This type of stream is mostly used by [Twitch]. The Web Display 
block supports two modes: raw HLS url (.m3u8 file) and Twitch channel with quality. The audio distance and volume is 
configurable in the display configuration as well as its size.

## Minefed render distance (Minecraft 1.20.4 / Fabric)
Displays follow the client's effective chunk render distance, including the server's view-distance
limit, instead of the default 64-block block-entity distance. For example, 16- and 32-chunk settings
use the same horizontal chunk boundary as terrain. Height differences do not shorten that range,
and changing the setting takes effect without reconnecting. The display's block must be in a loaded,
visible chunk; normal terrain occlusion and frustum culling still apply. Audio distance remains
controlled by the display's audio setting.

Client verification: place a static image beyond 64 blocks, check it near the 16- and 32-chunk
boundaries (also diagonally and at a different height), then lower/raise render distance and move
out of/back into range. Allow time for chunk loading and the initial image download. Repeat on a
server with a smaller view distance to check the effective limit. This is a client rendering fix;
clients need the patched JAR. Build target: Minecraft 1.20.4, Fabric Loader 0.16.14, Java 17.

## Supported systems
For now, and in order to distribute a single JAR file, this mod supports a limited set of systems because of the size 
of the [FFmpeg] library and other native binaries that needs to be bundled with it:
- Linux arm64
- Linux x86_64
- OSX arm64 (M1 support)
- OSX x86_64
- Windows x86_64

If your platform is not supported, please open an issue on GitHub, we need to know which systems are missing. However, 
x86 (32bits) systems might never be supported, or only in a separated JAR file.

## Supported languages
This project uses languages files for user interface texts, and currently supports **french (france)** and **english 
(us)**.

## Problems reporting
The technology behind it is very complicated to get the video playing at the right frame rate with a synchronized audio.
This might not work for all of you, so if you encounter problems, please report them on the [issue tracker] with the 
logs file of the session that didn't work.

[HLS]: https://fr.wikipedia.org/wiki/HTTP_Live_Streaming
[Twitch]: https://www.twitch.tv
[FFmpeg]: https://ffmpeg.org/
[issue tracker]: https://github.com/mindstorm38/fabric-webstreamer/issues
