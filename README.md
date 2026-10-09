# glTF Area Exporter (Fabric, Minecraft 26.3)

Select a box in the world and export everything in it to a single **.glb** (binary glTF 2.0) file:

- **Blocks** – exact in-game block models (stairs, fences, multipart, random variants, offsets like flowers/grass).
  Touching faces are welded: neighbouring blocks share their edge vertices, so a wall or floor is one connected
  surface rather than a separate island per block face. UVs are anchored to the world (shifted by whole texture
  repeats, which is invisible) so adjacent faces line up exactly; where biome tints differ, the shared vertex blends
  them. Faces meeting at an angle keep separate vertices so edges stay sharp.
  Flat models (flowers, grass, saplings, rails...) get a single quad per plane with a double-sided material instead
  of the game's separate front and back faces.
- **Fluids** – water and lava surfaces, using the game's own fluid renderer (modded fluids via Fabric API).
- **Block entities** – chests, beds, signs, banners (with patterns), skulls, shulker boxes, etc.
- **Entities** – mobs, players, armour stands, item frames, boats, minecarts, dropped items, including armour
  layers, held items, capes and other render layers.
- **Rigs** – every entity model is exported as a **skinned mesh with a skeleton**: one bone per model part, named after
  the part (`head`, `body`, `left_arm`, `right_hind_leg`...), posed as it was in-game.
  - Each entity is a node placed in the scene at its feet and turned to face where it faced; inside that node the
    entity faces +Z.
  - The armature sits at that node's origin with no translation or rotation.
  - Bones have no rest rotation, only their pivot positions; the in-game pose is kept in the mesh. Rotating a bone
    swings that part around its real pivot, ready to re-pose or animate.
  - Block entities (chests, beds...) are placed the same way, with the origin at the bottom centre of their block.
- **Textures** – every texture is embedded as a PNG, pixel-perfect (nearest-neighbour sampling, cut-out/translucent
  alpha set per material, emissive for eyes layers).
- **Tint and colouration** – biome grass/foliage/water colours, redstone power colour, stem colours, dyed leather,
  sheep wool, banner pattern colours, tropical fish, etc. are stored as **vertex colours** (`COLOR_0`), which glTF
  multiplies with the texture. Each texture is embedded only once, however many colours it is used with.

Scale is 1 block = 1 metre, Y up. The model's origin is the minimum corner of the selection.

## Usage

Hold a **golden hoe**: **left-click** a block for corner 1, **right-click** a block for corner 2. The selection is
outlined with particles. Then run:

```
/gltf export my_build
```

The file is written to `.minecraft/gltf_exports/my_build.glb`. There is **no size limit**: big selections are
automatically chunked (see below) and the export runs in the background while you keep playing.

| Command | What it does |
|---|---|
| `/gltf pos1` / `/gltf pos2` | Set a corner to the block you're looking at (or your feet) |
| `/gltf pos1 <x> <y> <z>` | Set a corner to exact coordinates |
| `/gltf export [name]` | Export (default name is a timestamp) |
| `/gltf info` | Show corners, size and options |
| `/gltf cancel` | Stop a running export |
| `/gltf clear` | Clear the selection |
| `/gltf set <option> <value>` | Change an option (below) |

| `/gltf config` | Open the options screen |

### Options

Defaults are saved to `config/gltfexport.json`. Change them on the options screen (**Mod Menu → glTF Area Exporter →
Config**, or `/gltf config`), or with `/gltf set <option> <value>`, which also saves.

| Option | Default | Meaning |
|---|---|---|
| `entities` | on | Export mobs, players, item frames, boats, dropped items... |
| `blockEntities` | on | Export chests, beds, signs, banners, skulls... |
| `fluids` | on | Export water and lava surfaces |
| `simpleBlockEntities` | off | Export chests, beds, signs, banners, skulls... as plain static geometry merged into the block mesh (no armature or separate node), posed as they are in-game |
| `includePlayer` | off | Include yourself if you are inside the selection |
| `closeEdges` | on | Keep block faces on the selection border so the model is closed. Off culls them against the real blocks outside, like the game does |
| `rigEntities` | on | Entities as skinned meshes with a skeleton. Off exports plain static meshes |
| `tints` | on | Grass/foliage/water/dye colours as vertex colours. Off exports untinted geometry |
| `mergeFaces` | on | Greedy meshing: flat runs of the same block face become one big quad with the texture repeating per block. Typically cuts block geometry by 10-50x |
| `normals` | on | Write vertex normals. Off: viewers/Blender use flat shading and files are ~30% smaller |
| `weldEdges` | off | Also join vertices where faces meet at an angle (block corners), averaging normals. For flat shading. See note below |
| `unlit` | off | `KHR_materials_unlit` materials for a flat in-game look |
| `partSize` | 512 | Split into several files every N blocks (0 = one file) |
| `tileSize` | 64 | Size of block mesh nodes inside a file |
| `showSelection` | on | Outline the selection with particles |
| `verboseLog` | off | Log every render call seen while capturing entities (for bug reports) |

### Large areas and chunking

- Capture is spread across game ticks (about 30 ms per tick), so the game never freezes; progress shows above the
  hotbar.
- **Parts:** a selection wider than `partSize` blocks (default 512) in X or Z is split into several files,
  `gltf_exports/<name>/<name>_<px>_<pz>.glb`, each covering `partSize × partSize` blocks at full height. All parts
  share one coordinate system, so importing them together reassembles the whole area seamlessly (faces between parts
  are culled correctly). Each part is written to disk and freed before the next is captured, so memory use stays
  bounded no matter how large the selection is. `/gltf set partSize 0` disables splitting (one file, up to the 4 GB
  .glb format limit).
- **Tiles:** inside each file, block geometry is split into nodes of `tileSize × tileSize` blocks (default 64)
  named `blocks_<tx>_<tz>`, which keeps individual meshes manageable in editors and lets viewers cull them.
- Files are streamed to disk, so there is no in-memory size cap on the output.
- Only chunks loaded on your client can be exported. Unloaded columns are left empty and the final message says how
  many; raise your render distance or fly over the area and export again.

## Building

Requires **JDK 25**.

```
./gradlew build
```

The mod jar is in `build/libs/gltf-export-1.0.0.jar`. Install it with Fabric Loader 0.19.5+ and Fabric API for 26.3.
It is client-side only and works on any server.

## Opening the file

- **Blender, fully connected mesh**: glTF can store only one UV per vertex, so a corner where faces use different parts
  of a texture always needs separate vertices in the file (`weldEdges` merges only the corners where UVs happen to
  match). For a mesh with every touching corner shared, tick **Merge Vertices** in Blender's glTF import options:
  Blender keeps UVs per face corner, so it can join them all, and with flat shading nothing changes visually.
- **Blender**: File → Import → glTF 2.0. Vertex colours import as the `Color` attribute and are wired into the
  material; if a version of Blender shows grass grey, add a *Color Attribute* node and multiply it with the image
  texture. Each entity imports as an armature with its mesh; block geometry is one
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
