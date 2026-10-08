# glTF Area Exporter (Fabric, Minecraft 26.3)

Select a box in the world and export everything in it to a single **.glb** (binary glTF 2.0) file:

- **Blocks** – exact in-game block models (stairs, fences, multipart, random variants, offsets like flowers/grass).
- **Fluids** – water and lava surfaces, using the game's own fluid renderer (modded fluids via Fabric API).
- **Block entities** – chests, beds, signs, banners (with patterns), skulls, shulker boxes, etc.
- **Entities** – mobs, players, armour stands, item frames, boats, minecarts, dropped items, including armour
  layers, held items, capes and other render layers.
- **Rigs** – every entity model is exported as a **skinned mesh with a skeleton**: one bone per model part, named after
  the part (`head`, `body`, `left_arm`, `right_hind_leg`...), posed as it was in-game. Re-pose or animate it in Blender.
- **Textures** – every texture is embedded as a PNG, pixel-perfect (nearest-neighbour sampling, cut-out/translucent
  alpha set per material, emissive for eyes layers).
- **Tint and colouration** – biome grass/foliage/water colours, redstone power colour, stem colours, dyed leather,
  sheep wool, banner pattern colours, tropical fish, etc. are **baked into the textures**, so the model looks the same
  in every viewer (no vertex-colour setup needed).

Scale is 1 block = 1 metre, Y up. The model's origin is the minimum corner of the selection.

## Usage

Hold a **golden hoe**: **left-click** a block for corner 1, **right-click** a block for corner 2. The selection is
outlined with particles. Then run:

```
/gltf export my_build
```

The file is written to `.minecraft/gltf_exports/my_build.glb`.

| Command | What it does |
|---|---|
| `/gltf pos1` / `/gltf pos2` | Set a corner to the block you're looking at (or your feet) |
| `/gltf pos1 <x> <y> <z>` | Set a corner to exact coordinates |
| `/gltf export [name]` | Export (default name is a timestamp) |
| `/gltf info` | Show corners, size and options |
| `/gltf clear` | Clear the selection |
| `/gltf set <option> <true\|false>` | Change an option (below) |

Options: `entities`, `blockEntities`, `fluids`, `includePlayer` (export yourself, off by default), `unlit` (adds
`KHR_materials_unlit` for a flat in-game look), `showSelection`.

The selection limit is 4,194,304 blocks (e.g. 256 × 64 × 256). Large exports pause the game for a few seconds while
geometry is captured; the file is written in the background.

## Building

Requires **JDK 25**.

```
./gradlew build
```

The mod jar is in `build/libs/gltf-export-1.0.0.jar`. Install it with Fabric Loader 0.19.5+ and Fabric API for 26.3.
It is client-side only and works on any server.

## Opening the file

- **Blender**: File → Import → glTF 2.0. Each entity imports as an armature with its mesh; block geometry is one
  mesh named `blocks`. If faces look dark, set the material Blend Mode/Shadow to Alpha Clip for cut-out materials
  (Blender 4.2+ does this automatically from the glTF alpha mode).
- Also works in three.js, Babylon.js, Godot, Unity (glTFast), Unreal (glTF importer), and online viewers.

## How it works

- **Blocks** are read from the baked block models (`BlockStateModel` → `BlockStateModelPart` → `BakedQuad`). Faces
  hidden by neighbours inside the selection are culled; faces on the selection border are kept so the export is
  closed. Tints come from `BlockColors#getTintSources` exactly as the vanilla renderer computes them.
- **Entities and block entities** are rendered through vanilla's own renderers into a capturing stand-in for the
  `SubmitNodeCollector`. Every submitted model is posed (`Model#setupAnim`) and its `ModelPart` tree becomes the
  skeleton. Because the real renderers are used, layers, held items and modded entities that use vanilla rendering
  come along automatically.
- The render-pipeline glue (dispatcher methods, model-part internals) is resolved by reflection by type rather than
  exact name, so minor signature changes in 26.3.x don't break the mod; anything that can't be captured is skipped and
  logged instead of crashing.

## Known limitations

- Animated textures export their first frame.
- Text on signs, name tags, shadows, particles, leads, beacon beams and enchantment glint are not exported.
- Entities that draw with fully custom shaders or immediate-mode code (rare, mostly mods) may be missing.
- Textures that exist only on the GPU and not in a resource pack or as a dynamic texture (rare) get a magenta/black
  placeholder; the chat message tells you how many.

## Continuous integration

`.github/workflows/build.yml` builds the mod with JDK 25 on every push and pull request (you can also run it by hand
from the Actions tab). The jar is attached to each run as the **gltf-export** artifact.

To publish a release, push a version tag:

```
git tag v1.0.0
git push origin v1.0.0
```

The workflow then creates a GitHub Release for that tag with the jar attached.
