# Departed dialogue HUD

The Java dialogue HUD uses the server resource pack and the vanilla action bar. It keeps the existing spoken/option messengers, dialogue mount, WASD/scroll selection, confirmation keys, voice dispatch and quest triggers. The legacy chat presentation is the default.

## Configuration

Edit `DepartedServer/server/plugins/Typewriter/config.yml.tpl`:

```yaml
dialogue:
  hud:
    enabled: false
    pages:
      TwBeeGod01: true
```

This example enables only conversations starting on page `TwBeeGod01`. Use the exact Typewriter page ID, normally the JSON filename without `.json`. Omit a page to inherit the global default. Set a page to `false` to keep chat when `enabled: true`.

The first dialogue entry selects the presentation for the whole conversation, including linked entries on other pages. It remains stable until that conversation ends. A later conversation resolves the configuration again. No presentation fields are added to individual entries, and existing page JSON needs no migration.

The launcher renders `.tpl` files at boot. Load the matching engine, extension and resource pack together, then restart to load the new binaries. Editing only the generated `config.yml` is not durable across launcher restarts.

## Presentation and ownership

- Player-style NPC speech appears to the right of the square character window, with the speaker name above it.
- Choices appear beneath the speech and character window. The existing selection index and input handlers determine the selected answer; rendering does not select or confirm answers.
- Option-less spoken lines use a shorter frame and a continue/finish instruction. They retain their existing confirmation behavior.
- One to four choices get a matching frame height. Longer lists scroll through the existing WASD selection. Selected responses that need more than two rows rotate through complete text pages; long speech similarly retains every page after it finishes typing or is skipped.
- The HUD owns the action bar for the active dialogue. Normal chat is neither blocked, cleared, darkened nor replayed by HUD messengers. Ownership is released when the conversation exits.
- Legacy chat remains available through the configuration. Floodgate's Bedrock forms retain their existing presentation; Java resource-pack shaders do not apply to Bedrock.

## Character preview

For live model framing tests, edit the running server's `plugins/Typewriter/config.yml`, then run
`/tw reload` and reopen the NPC conversation. `dialogue.hud.framing.npcs.<npc-id>.offset-x`, `offset-y`, `scale`
and `focus` override their global settings. Keep the same values in `config.yml.tpl` for persistence: the launcher renders that
template at startup, while Typewriter reloads the generated `config.yml` directly. A new engine JAR requires
one server restart before its updated reload behavior is available.

The preview uses a viewer-only rendering carrier and the NPC's existing resources. It does not replace or move the original NPC, change the player's camera, or require an image file per character. The player-style preview uses the cached skin, including mirror skins and the skin's slim/classic model selection.

Model NPCs use an independent DepartedModels instance of their existing model with the original textures and configured animation, shown only to the dialogue player; without DepartedModels they get no portrait. Player-limb models reuse their configured or mirrored skin. The window frames itself from the model's cubes in its live pose: a bust aimed at the head when that is a closer shot than the whole body, the whole body centred otherwise. A model whose `.bbmodel` cannot be read is framed from its hit box. Ordinary `MOB` NPCs do not yet have a dedicated portrait renderer.

The 3D preview renders in the world pass while the text and frame render in the GUI pass. Minecraft does not report the F1 hide-GUI setting to the server, so the preview can remain visible when F1 hides the frame. This is a vanilla transport limitation, not a client mod requirement.

Shader compilation and protocol arithmetic can be checked automatically. Actual Minecraft rendering, resource-pack merge behavior, third-person views, F1, GUI scales, client versions and GPU compatibility require in-game verification before enabling the HUD globally. The default configuration leaves every existing conversation on chat.

## Build

From `engine/`, run `gradlew.bat build buildRelease`; from `extensions/`, run `gradlew.bat :DepartedMMORPGExtension:test :DepartedMMORPGExtension:assemble`. Deploy `jars/engine/Typewriter-0.9.0.jar` to the server's `jars/` and the extension's `build/libs/DepartedMMORPGExtension-0.0.1-all.jar` as `server/plugins/Typewriter/extensions/DepartedMMORPGExtension-0.0.1.jar`. The engine release task includes the existing web editor from `app/build/web`.
