package com.slow3586;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.slow3586.Main.Settings.Node.ExternalResource;
import io.vavr.Function1;
import io.vavr.Function3;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.jooq.lambda.Sneaky;
import com.slow3586.Main.Room.RoomStyle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.slow3586.Main.Color.WHITE;
import static com.slow3586.Main.Configuration.parseConfigEntry;
import static com.slow3586.Main.MapTile.TileType.WALL;
import static com.slow3586.Main.Settings.*;
import static com.slow3586.Main.Settings.Node.AsNonPhysical.AS_NON_PHYSICAL_DEFAULT;
import static com.slow3586.Main.Settings.Node.AsPhysical.AS_PHYSICAL_DEFAULT;
import static com.slow3586.Main.Settings.Node.ExternalResource.BASE_PNG_TEXTURE_FILENAME;
import static com.slow3586.Main.Settings.Node.ExternalResource.CRATE_BLOCKING;
import static com.slow3586.Main.Settings.Node.ExternalResource.CRATE_NON_BLOCKING;
import static com.slow3586.Main.Settings.Node.ExternalResource.DOMAIN_FOREGROUND;
import static com.slow3586.Main.Settings.Node.ExternalResource.DOMAIN_PHYSICAL;
import static com.slow3586.Main.Settings.Node.ExternalResource.LINE_FLOOR;
import static com.slow3586.Main.Settings.Node.ExternalResource.LINE_WALL;
import static com.slow3586.Main.Settings.Node.ExternalResource.RESOURCE_FLOOR_ID;
import static com.slow3586.Main.Settings.Node.ExternalResource.MAP_GFX_PATH;
import static com.slow3586.Main.Settings.Node.ExternalResource.RESOURCE_ID_PREFIX;
import static com.slow3586.Main.Settings.Node.ExternalResource.PNG_EXT;
import static com.slow3586.Main.Settings.Node.ExternalResource.RESOURCE_WALL_ID;
import static com.slow3586.Main.Settings.Node.ExternalResource.ROOM_NOISE_CIRCLE;
import static com.slow3586.Main.Settings.Node.ExternalResource.SHADOW_FLOOR_CORNER;
import static com.slow3586.Main.Settings.Node.ExternalResource.SHADOW_FLOOR_LINE;
import static com.slow3586.Main.Settings.Node.ExternalResource.SHADOW_WALL_CORNER;
import static com.slow3586.Main.Settings.Node.ExternalResource.SHADOW_WALL_LINE;
import static com.slow3586.Main.Size.TILE_SIZE;

public class Main {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static ConfigurationRandom configRandom;
    static Random baseRandom;
    static Configuration config;

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public static void main(String[] args) throws IOException {
        //region CONFIGURATION
        final String configRandomStr = Files.readString(Path.of("config_random.json"));
        configRandom = OBJECT_MAPPER.readValue(configRandomStr, ConfigurationRandom.class);
        baseRandom = new Random(configRandom.seed);

        final String configStr = Files.readString(Path.of("config.json"));
        config = OBJECT_MAPPER.readValue(configStr, Configuration.class);

        final Path mapDirectory = Path.of(config.gameDirectoryPath, "user", "projects", config.mapName);
        //endregion

        //region GENERATION: ROOMS
        //region RANDOMIZE DIAGONAL ROOM SIZES
        final Size[] diagonalRoomSizes = Stream.generate(() -> config.roomMinMaxSize.randomize())
            .limit(Math.max(config.roomsCount.x, config.roomsCount.y))
            .toArray(Size[]::new);
        //endregion

        //region RANDOMIZE ROOM STYLES
        final RoomStyle[] styles = IntStream.range(0, config.styleCount)
            .boxed()
            .map(styleIndex ->
                new RoomStyle(
                    styleIndex,
                    config.floorMinMaxTintBase
                        .randomize()
                        .add(config.floorTintPerHeight.mul(styleIndex)),
                    config.wallMinMaxTintBase
                        .randomize()
                        .add(config.wallTintPerHeight.mul(styleIndex)),
                    nextInt(0, config.patternResourceCount),
                    nextInt(0, config.patternResourceCount),
                    config.patternMinMaxTintFloor.randomize(),
                    config.patternMinMaxTintWall.randomize())
            ).toArray(RoomStyle[]::new);
        //endregion

        final Room[][] rooms = new Room[config.roomsCount.y][config.roomsCount.x];
        pointsRect(0, 0, config.roomsCount.x, config.roomsCount.y)
            .forEach(roomIndex -> {
                //region CALCULATE ABSOLUTE ROOM POSITION
                final Point roomPosAbs = new Point(
                    Arrays.stream(diagonalRoomSizes)
                        .limit(roomIndex.x)
                        .map(Size::getW)
                        .reduce(0, Integer::sum),
                    Arrays.stream(diagonalRoomSizes)
                        .limit(roomIndex.y)
                        .map(Size::getH)
                        .reduce(0, Integer::sum));
                //endregion

                //region RANDOMIZE WALL
                final Size wallSize = new Size(
                    nextInt(config.wallMinMaxSize.min.w, config.wallMinMaxSize.max.w),
                    nextInt(config.wallMinMaxSize.min.h, config.wallMinMaxSize.max.h));
                final Point wallOffset = new Point(
                    -nextInt(0, Math.min(wallSize.w, config.wallMaxOffset.x)),
                    -nextInt(0, Math.min(wallSize.h, config.wallMaxOffset.y)));
                //endregion

                //region CALCULATE BASE ROOM SIZE
                final Size realRoomSize = new Size(
                    diagonalRoomSizes[roomIndex.x].w + wallOffset.x,
                    diagonalRoomSizes[roomIndex.y].h + wallOffset.y);
                //endregion

                //region RANDOMIZE DOOR
                final boolean needVerticalDoor = roomIndex.x > 0 && roomIndex.x < rooms[0].length - 1;
                final boolean needHorizontalDoor = roomIndex.y > 0 && roomIndex.y < rooms.length - 1;
                final Size doorSize = new Size(
                    needHorizontalDoor
                        ? nextInt(config.doorMinMaxWidth.min.w,
                        Math.min(config.doorMinMaxWidth.max.w, realRoomSize.w))
                        : 0,
                    needVerticalDoor
                        ? nextInt(config.doorMinMaxWidth.min.h,
                        Math.min(config.doorMinMaxWidth.max.h, realRoomSize.h))
                        : 0);
                final Point doorOffset = new Point(
                    needHorizontalDoor
                        ? nextInt(1, config.doorMinMaxWidth.min.w + realRoomSize.w - doorSize.w)
                        : 0,
                    needVerticalDoor
                        ? nextInt(1, config.doorMinMaxWidth.min.h + realRoomSize.h - doorSize.h)
                        : 0);
                //endregion

                //region RANDOMIZE STYLE
                final int styleIndex = nextInt(0, config.styleCount);
                final Size styleSize = new Size(
                    roomIndex.x == rooms[0].length - 1
                        ? 1
                        : nextInt(config.styleSizeMinMaxSize.min.w, config.styleSizeMinMaxSize.max.w + 1),
                    roomIndex.y == rooms.length - 1
                        ? 1
                        : nextInt(config.styleSizeMinMaxSize.min.h, config.styleSizeMinMaxSize.max.h + 1));
                //endregion

                //region PUT ROOM INTO ROOMS ARRAY
                rooms[roomIndex.y][roomIndex.x] = new Room(
                    roomPosAbs,
                    new Size(
                        diagonalRoomSizes[roomIndex.x].w,
                        diagonalRoomSizes[roomIndex.y].h),
                    new Room.Rect(
                        wallOffset.x,
                        wallSize.w),
                    new Room.Rect(
                        wallOffset.y,
                        wallSize.h),
                    new Room.Rect(
                        doorOffset.x,
                        doorSize.w),
                    new Room.Rect(
                        doorOffset.y,
                        doorSize.h),
                    styleIndex,
                    styleSize);
                //endregion
            });
        //endregion

        //region GENERATION: BASE MAP TILE ARRAY
        final MapTile[][] mapTilesUncropped =
            pointsRectRows(
                Arrays.stream(diagonalRoomSizes)
                    .mapToInt(r -> r.w + config.wallMaxOffset.x)
                    .sum() + 1,
                Arrays.stream(diagonalRoomSizes)
                    .mapToInt(r -> r.h + config.wallMaxOffset.y)
                    .sum() + 1)
                .stream()
                .map(row -> row.stream()
                    .map(point -> new MapTile(
                        MapTile.TileType.FLOOR,
                        null,
                        false,
                        true,
                        null))
                    .toArray(MapTile[]::new)
                ).toArray(MapTile[][]::new);
        //endregion

        //region GENERATION: RENDER BASE ROOMS ONTO BASE MAP TILE ARRAY
        pointsRectArray(rooms)
            .forEach(roomIndex -> {
                final Room room = rooms[roomIndex.y][roomIndex.x];

                //region FILL MAP TILES
                //region WALL HORIZONTAL
                pointsRect(
                    room.roomPosAbs.x,
                    room.roomPosAbs.y + room.roomSize.h + room.wallHoriz.offset,
                    room.roomSize.w,
                    room.wallHoriz.width
                ).forEach(pointAbs ->
                    mapTilesUncropped[pointAbs.y][pointAbs.x].tileType =
                        (mapTilesUncropped[pointAbs.y][pointAbs.x].tileType == MapTile.TileType.DOOR)
                            || (pointAbs.x >= room.roomPosAbs.x + room.doorHoriz.offset
                            && pointAbs.x < room.roomPosAbs.x + room.doorHoriz.offset + room.doorHoriz.width)
                            ? MapTile.TileType.DOOR
                            : WALL);
                //endregion

                //region WALL VERTICAL
                pointsRect(
                    room.roomPosAbs.x + room.roomSize.w + room.wallVert.offset,
                    room.roomPosAbs.y,
                    room.wallVert.width,
                    room.roomSize.h
                ).forEach(pointAbs ->
                    mapTilesUncropped[pointAbs.y][pointAbs.x].tileType =
                        (mapTilesUncropped[pointAbs.y][pointAbs.x].tileType == MapTile.TileType.DOOR)
                            || (pointAbs.y >= room.roomPosAbs.y + room.doorVert.offset
                            && pointAbs.y < room.roomPosAbs.y + room.doorVert.offset + room.doorVert.width)
                            ? MapTile.TileType.DOOR
                            : WALL);
                //endregion

                //region CARCASS HORIZONTAL
                pointsRect(
                    room.roomPosAbs.x,
                    room.roomPosAbs.y + room.roomSize.h,
                    room.roomSize.w,
                    1
                ).forEach(pointAbs ->
                    mapTilesUncropped[pointAbs.y][pointAbs.x].carcass = true);
                //endregion

                //region CARCASS VERTICAL
                pointsRect(
                    room.roomPosAbs.x + room.roomSize.w,
                    room.roomPosAbs.y,
                    1,
                    room.roomSize.h
                ).forEach(pointAbs ->
                    mapTilesUncropped[pointAbs.y][pointAbs.x].carcass = true);
                //endregion

                //region TILE ROOM TYPE
                pointsRect(
                    room.roomPosAbs.x,
                    room.roomPosAbs.y,
                    room.roomSize.w + room.styleSize.w,
                    room.roomSize.h + room.styleSize.h
                ).stream()
                    .map(pointAbs -> mapTilesUncropped[pointAbs.y][pointAbs.x])
                    .forEach(tile -> {
                        tile.styleIndex = room.styleIndex;
                        tile.height = styles[room.styleIndex].height
                            + (tile.tileType == WALL
                            ? config.wallHeight
                            : 0);
                    });
                //endregion
                //endregion
            });
        //endregion

        //region GENERATION: CROP MAP
        final MapTile[][] mapTilesCrop;
        if (config.cropMap) {
            final Size croppedMapSize = new Size(
                Arrays.stream(diagonalRoomSizes)
                    .limit(rooms[0].length)
                    .map(s -> s.w)
                    .reduce(0, Integer::sum)
                    - diagonalRoomSizes[0].w
                    + 1,
                Arrays.stream(diagonalRoomSizes)
                    .limit(rooms.length)
                    .map(s -> s.h)
                    .reduce(0, Integer::sum)
                    - diagonalRoomSizes[0].h
                    + 1);

            final MapTile[][] temp = new MapTile[croppedMapSize.h][croppedMapSize.w];
            for (int y = 0; y < croppedMapSize.h; y++) {
                temp[y] = Arrays.copyOfRange(
                    mapTilesUncropped[y + diagonalRoomSizes[0].h],
                    diagonalRoomSizes[0].w,
                    diagonalRoomSizes[0].w + croppedMapSize.w);
            }

            mapTilesCrop = temp;
        } else {
            mapTilesCrop = mapTilesUncropped;
        }
        //endregion

        //region GENERATION: FIX MOST DOWN RIGHT TILE
        mapTilesCrop[mapTilesCrop.length - 1][mapTilesCrop[0].length - 1] =
            mapTilesCrop[mapTilesCrop.length - 1][mapTilesCrop[0].length - 2];
        //endregion

        //region GENERATION: FIX DIAGONAL WALLS TOUCH WITH EMPTY SIDES
        // #_    _#
        // _# OR #_
        final Function1<MapTile, Boolean> isFloor = (s) -> s.tileType == MapTile.TileType.FLOOR
            || s.tileType == MapTile.TileType.DOOR;
        for (int iter = 0; iter < 2; iter++) {
            for (int y = 1; y < mapTilesCrop.length - 1; y++) {
                for (int x = 1; x < mapTilesCrop[y].length - 1; x++) {
                    final boolean floor = isFloor.apply(mapTilesCrop[y][x]);
                    final boolean floorR = isFloor.apply(mapTilesCrop[y][x + 1]);
                    final boolean floorD = isFloor.apply(mapTilesCrop[y + 1][x]);
                    final boolean floorRD = isFloor.apply(mapTilesCrop[y + 1][x + 1]);
                    if ((floor && floorRD && !floorR && !floorD)
                        || (!floor && !floorRD && floorR && floorD)
                    ) {
                        if (!isFloor.apply(mapTilesCrop[y][x]))
                            mapTilesCrop[y][x].height -= config.wallHeight;
                        mapTilesCrop[y][x].tileType = MapTile.TileType.FLOOR;
                        if (!isFloor.apply(mapTilesCrop[y][x + 1]))
                            mapTilesCrop[y][x + 1].height -= config.wallHeight;
                        mapTilesCrop[y][x + 1].tileType = MapTile.TileType.FLOOR;
                        if (!isFloor.apply(mapTilesCrop[y + 1][x]))
                            mapTilesCrop[y + 1][x].height -= config.wallHeight;
                        mapTilesCrop[y + 1][x].tileType = MapTile.TileType.FLOOR;
                        if (!isFloor.apply(mapTilesCrop[y + 1][x + 1]))
                            mapTilesCrop[y + 1][x + 1].height -= config.wallHeight;
                        mapTilesCrop[y + 1][x + 1].tileType = MapTile.TileType.FLOOR;
                    }
                }
            }
        }
        //endregion

        //region OUTPUT: PRINT MAP TO TEXT FILE
        final StringJoiner wallJoiner = new StringJoiner("\n");
        wallJoiner.add("Walls:");
        final StringJoiner heightJoiner = new StringJoiner("\n");
        heightJoiner.add("Heights:");
        final StringJoiner styleIndexJoiner = new StringJoiner("\n");
        styleIndexJoiner.add("Styles:");
        final StringJoiner carcassJoiner = new StringJoiner("\n");
        carcassJoiner.add("Carcass:");

        pointsRectArrayByRow(mapTilesCrop)
            .forEach(row -> {
                final StringBuilder wallJoinerRow = new StringBuilder();
                final StringBuilder heightJoinerRow = new StringBuilder();
                final StringBuilder styleIndexJoinerRow = new StringBuilder();
                final StringBuilder carcassJoinerRow = new StringBuilder();
                row.forEach(point -> {
                    final MapTile mapTile = mapTilesCrop[point.y][point.x];
                    wallJoinerRow.append(
                        mapTile.tileType == WALL
                            ? "#"
                            : mapTile.tileType == MapTile.TileType.DOOR
                                ? "."
                                : "_");
                    heightJoinerRow.append(mapTile.height);
                    styleIndexJoinerRow.append(mapTile.styleIndex);
                    carcassJoinerRow.append(
                        mapTile.carcass || point.x == 0 || point.y == 0
                            ? "#"
                            : "_");
                });
                wallJoiner.add(wallJoinerRow.toString());
                heightJoiner.add(heightJoinerRow.toString());
                styleIndexJoiner.add(styleIndexJoinerRow.toString());
                carcassJoiner.add(carcassJoinerRow.toString());
            });

        final StringJoiner textJoiner = new StringJoiner("\n\n");
        textJoiner.add(carcassJoiner.toString());
        textJoiner.add(wallJoiner.toString());
        textJoiner.add(heightJoiner.toString());
        textJoiner.add(styleIndexJoiner.toString());

        Files.write(Path.of(config.outputTextFilePath), textJoiner.toString().getBytes());
        //endregion

        //region OUTPUT: CREATE MAP JSON FILE
        //region BASE MAP JSON OBJECT
        final Map mapJson = new Map(
            new Meta(
                config.gameVersion,
                config.mapName,
                "2023-11-14 17:28:36.619839 UTC"),
            new About("Generated map"),
            new Settings(
                "bomb_defusal",
                config.ambientLightColor.intArray()),
            new Playtesting(Playtesting.QUICK_TEST),
            new ArrayList<>(),
            List.of(new Layer(
                "default",
                new ArrayList<>())),
            new ArrayList<>());
        //endregion

        //region RESOURCES: FUNCTIONS
        final File texturesDir = new File("textures");
        final Path mapGfxPath = mapDirectory.resolve("gfx");
        if (!Files.exists(mapGfxPath)) {
            Files.createDirectories(mapGfxPath);
        }
        final BiConsumer<String, String> createTexture = Sneaky.biConsumer(
            (sourceFilename, targetFilename) -> {
                final Path sourcePath = texturesDir.toPath().resolve(sourceFilename + PNG_EXT);
                final Path targetPath = mapGfxPath.resolve(targetFilename + PNG_EXT);
                if (Files.exists(targetPath))
                    Files.delete(targetPath);
                Files.copy(sourcePath, targetPath);
            });
        final Consumer<String> createTextureSameName = Sneaky.consumer(
            (filename) -> createTexture.accept(filename, filename));
        //endregion

        //region RESOURCES: ROOMS
        mapJson.external_resources.add(
            ExternalResource.builder()
                .path(MAP_GFX_PATH + ROOM_NOISE_CIRCLE + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + ROOM_NOISE_CIRCLE)
                .stretch_when_resized(true)
                .domain(DOMAIN_FOREGROUND)
                .color(new Color(255, 255, 255, 150).intArray())
                .build());
        createTextureSameName.accept(ROOM_NOISE_CIRCLE);
        //endregion

        //region RESOURCES: STYLES
        final Function3<Integer, Integer, Boolean, String> getCrateName = (
            final Integer roomStyleIndex,
            final Integer crateStyleIndex,
            final Boolean isBlocking
        ) -> "style"
            + roomStyleIndex
            + "_crate"
            + crateStyleIndex
            + "_"
            + (isBlocking ? "" : "non")
            + "blocking";
        IntStream.range(0, styles.length)
            .boxed()
            .forEach((final Integer roomStyleIndex) -> {
                final RoomStyle style = styles[roomStyleIndex];
                final String floorId = RESOURCE_FLOOR_ID + roomStyleIndex;
                mapJson.external_resources.add(
                    ExternalResource.builder()
                        .path(MAP_GFX_PATH + floorId + PNG_EXT)
                        .id(RESOURCE_ID_PREFIX + floorId)
                        .color(style.floorColor.intArray())
                        .build());
                createTexture.accept(BASE_PNG_TEXTURE_FILENAME, floorId);

                final String wallId = RESOURCE_WALL_ID + roomStyleIndex;
                mapJson.external_resources.add(
                    ExternalResource.builder()
                        .path(MAP_GFX_PATH + wallId + PNG_EXT)
                        .id(RESOURCE_ID_PREFIX + wallId)
                        .domain(DOMAIN_PHYSICAL)
                        .color(style.wallColor.intArray())
                        .as_physical(AS_PHYSICAL_DEFAULT)
                        .build());
                createTexture.accept(BASE_PNG_TEXTURE_FILENAME, wallId);

                final String patternWallTarget = "style" + roomStyleIndex + "_pattern_wall";
                mapJson.external_resources.add(
                    ExternalResource.builder()
                        .path(MAP_GFX_PATH + patternWallTarget + PNG_EXT)
                        .id(RESOURCE_ID_PREFIX + patternWallTarget)
                        .domain(DOMAIN_FOREGROUND)
                        .color(style.patternColorWall.intArray())
                        .build());
                createTexture.accept("pattern" + style.patternIdWall, patternWallTarget);

                final String patternFloorTarget = "style" + roomStyleIndex + "_pattern_floor";
                mapJson.external_resources.add(
                    ExternalResource.builder()
                        .path(MAP_GFX_PATH + patternFloorTarget + PNG_EXT)
                        .id(RESOURCE_ID_PREFIX + patternFloorTarget)
                        .color(style.patternColorFloor.intArray())
                        .build());
                createTexture.accept("pattern" + style.patternIdFloor, patternFloorTarget);
                final BiConsumer<Integer, Boolean> createCrate = (
                    final Integer crateStyleIndex,
                    final Boolean isBlocking
                ) -> {
                    final String crateName = getCrateName.apply(
                        roomStyleIndex,
                        crateStyleIndex,
                        isBlocking);
                    mapJson.external_resources.add(
                        ExternalResource.builder()
                            .path(MAP_GFX_PATH + crateName + PNG_EXT)
                            .id(RESOURCE_ID_PREFIX + crateName)
                            .color(WHITE.intArray())
                            .domain(DOMAIN_PHYSICAL)
                            .stretch_when_resized(true)
                            .size(config.crateMinMaxSize.randomize().floatArray())
                            .color((isBlocking
                                ? config.crateBlockingMinMaxTint
                                : config.crateNonBlockingMinMaxTint)
                                .randomize()
                                .intArray()
                            ).as_physical(Node.AsPhysical.builder()
                                .custom_shape(Node.AsPhysical.CustomShape.CRATE_SHAPE)
                                .is_see_through(!isBlocking)
                                .is_shoot_through(!isBlocking)
                                .is_melee_throw_through(!isBlocking)
                                .is_throw_through(!isBlocking)
                                .density(nextFloat(0.6f, 1.3f))
                                .friction(nextFloat(0.0f, 0.5f))
                                .bounciness(nextFloat(0.1f, 0.6f))
                                .penetrability(nextFloat(0.0f, 1.0f))
                                .angular_damping(nextFloat(10f, 100f))
                                .build())
                            .build());
                    createTexture.accept(isBlocking ? CRATE_BLOCKING : CRATE_NON_BLOCKING, crateName);
                };

                IntStream.range(0, config.cratesBlockingPerStyle)
                    .forEach(crateIndex -> createCrate.accept(crateIndex, true));
                IntStream.range(0, config.cratesNonBlockingPerStyle)
                    .forEach(crateIndex -> createCrate.accept(crateIndex, false));
            });
        //endregion

        //region SHADOWS 1: ADD SHADOW RESOURCES
        mapJson.external_resources.add(
            ExternalResource.builder()
                .path(MAP_GFX_PATH + SHADOW_WALL_CORNER + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + SHADOW_WALL_CORNER)
                .domain(DOMAIN_FOREGROUND)
                .color(config.shadowTintWall.intArray())
                .as_nonphysical(AS_NON_PHYSICAL_DEFAULT)
                .build());
        createTextureSameName.accept(SHADOW_WALL_CORNER);

        mapJson.external_resources.add(
            ExternalResource.builder()
                .path(MAP_GFX_PATH + SHADOW_WALL_LINE + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + SHADOW_WALL_LINE)
                .domain(DOMAIN_FOREGROUND)
                .color(config.shadowTintWall.intArray())
                .as_nonphysical(AS_NON_PHYSICAL_DEFAULT)
                .build());
        createTextureSameName.accept(SHADOW_WALL_LINE);

        mapJson.external_resources.add(
            ExternalResource.builder()
                .path(MAP_GFX_PATH + SHADOW_FLOOR_LINE + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + SHADOW_FLOOR_LINE)
                .color(config.shadowTintFloor.intArray())
                .domain(DOMAIN_FOREGROUND)
                .as_nonphysical(AS_NON_PHYSICAL_DEFAULT)
                .build());
        createTextureSameName.accept(SHADOW_FLOOR_LINE);

        mapJson.external_resources.add(
            ExternalResource.builder()
                .path(MAP_GFX_PATH + SHADOW_FLOOR_CORNER + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + SHADOW_FLOOR_CORNER)
                .color(config.shadowTintFloor.intArray())
                .domain(DOMAIN_FOREGROUND)
                .as_nonphysical(AS_NON_PHYSICAL_DEFAULT)
                .build());
        createTextureSameName.accept(SHADOW_FLOOR_CORNER);

        mapJson.external_resources.add(
            ExternalResource.builder()
                .path(MAP_GFX_PATH + LINE_FLOOR + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + LINE_FLOOR)
                .color(config.blackLineFloorTint.intArray())
                .as_nonphysical(AS_NON_PHYSICAL_DEFAULT)
                .build());
        createTextureSameName.accept(LINE_FLOOR);

        mapJson.external_resources.add(
            ExternalResource.builder()
                .path(MAP_GFX_PATH + LINE_WALL + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + LINE_WALL)
                .color(config.blackLineWallTint.intArray())
                .as_nonphysical(AS_NON_PHYSICAL_DEFAULT)
                .build());
        createTextureSameName.accept(LINE_WALL);
        //endregion

        //region SHADOWS 2: CALCULATE SHADOW TILES
        pointsRectArray(mapTilesCrop).forEach(thisPoint -> {
            final MapTile currentTile = mapTilesCrop[thisPoint.y][thisPoint.x];
            final boolean thisIsWall = !isFloor.apply(currentTile);
            final Function1<Point, ShadowCalcTileInfo.Entry> getEntry = (thatPointAdd) -> {
                final Point thatPoint = thisPoint.add(thatPointAdd);
                final MapTile otherTile = (thatPoint.x < 0
                    || thatPoint.y < 0
                    || thatPoint.x >= mapTilesCrop[0].length
                    || thatPoint.y >= mapTilesCrop.length)
                    ? MapTile.VIRTUAL_TILE
                    : mapTilesCrop[thatPoint.y][thatPoint.x];
                final boolean thatIsWall = otherTile.tileType == WALL;
                final int hDif = otherTile.height - currentTile.height;
                final boolean sameStyle = Objects.equals(otherTile.styleIndex, currentTile.styleIndex);
                final boolean needLine = hDif != 0 || !sameStyle;
                final boolean higher = hDif > 0;
                return new ShadowCalcTileInfo.Entry(
                    hDif,
                    thatIsWall,
                    sameStyle,
                    needLine,
                    higher);
            };
            final ShadowCalcTileInfo info = new ShadowCalcTileInfo(
                getEntry.apply(Point.LEFT),
                getEntry.apply(Point.UP),
                getEntry.apply(Point.RIGHT),
                getEntry.apply(Point.DOWN),
                getEntry.apply(Point.UP_LEFT),
                getEntry.apply(Point.UP_RIGHT),
                getEntry.apply(Point.DOWN_LEFT),
                getEntry.apply(Point.DOWN_RIGHT));

            mapJson.addShadowLine(thisPoint, 0, info.left.hDif, thisIsWall);
            mapJson.addShadowLine(thisPoint, 90, info.up.hDif, thisIsWall);
            mapJson.addShadowLine(thisPoint, 180, info.right.hDif, thisIsWall);
            mapJson.addShadowLine(thisPoint, -90, info.down.hDif, thisIsWall);
            mapJson.addShadowCorner(thisPoint, 0,
                info.downLeft.hDif - Math.max(info.down.hDif, info.left.hDif),
                thisIsWall);
            mapJson.addShadowCorner(thisPoint, 90,
                info.upLeft.hDif - Math.max(info.up.hDif, info.left.hDif),
                thisIsWall);
            mapJson.addShadowCorner(thisPoint, 180,
                info.upRight.hDif - Math.max(info.up.hDif, info.right.hDif),
                thisIsWall);
            mapJson.addShadowCorner(thisPoint, -90,
                info.downRight.hDif - Math.max(info.down.hDif, info.right.hDif),
                thisIsWall);
            if (info.left.needLine) mapJson.addBlackLine(thisPoint, 0, thisIsWall);
            if (info.up.needLine) mapJson.addBlackLine(thisPoint, 90, thisIsWall);
            if (info.right.needLine) mapJson.addBlackLine(thisPoint, 180, thisIsWall);
            if (info.down.needLine) mapJson.addBlackLine(thisPoint, -90, thisIsWall);
        });
        //endregion

        //region NODES: ROOM EFFECTS
        pointsRectArray(rooms).forEach(point -> {
            if (point.x == 0 || point.y == 0) return;
            final Room room = rooms[point.y][point.x];
            final Point roomEffectPosAbs;
            while (true) {
                final Point roomEffectPos = new Point(
                    (room.roomPosAbs.x + nextInt(0, room.roomSize.h) - diagonalRoomSizes[0].w),
                    (room.roomPosAbs.y + nextInt(0, room.roomSize.h) - diagonalRoomSizes[0].h));
                if (roomEffectPos.x >= mapTilesCrop[0].length || roomEffectPos.y >= mapTilesCrop.length) {
                    continue;
                }
                final MapTile mapTile = mapTilesCrop[roomEffectPos.y][roomEffectPos.x];
                if (mapTile.visible && mapTile.tileType != WALL) {
                    roomEffectPosAbs = roomEffectPos.mul(TILE_SIZE.toPoint());
                    break;
                }
            }
            final Color effectColor = config.roomLightMinMaxTint.randomize();
            final float[] effectSize = {
                room.roomSize.w * TILE_SIZE.w * (1 + nextFloat(1, 4) / 4),
                room.roomSize.h * TILE_SIZE.h * (1 + nextFloat(1, 4) / 4)
            };
            mapJson.addNode(Node.builder()
                .type(RESOURCE_ID_PREFIX + ROOM_NOISE_CIRCLE)
                .pos(roomEffectPosAbs.floatArray())
                .size(effectSize)
                .rotation((float) nextInt(1, 359))
                .color(effectColor.intArray())
                .build());
            mapJson.addNode(Node.builder()
                .type(ExternalResource.WANDERING_PIXELS)
                .pos(roomEffectPosAbs.floatArray())
                .size(effectSize)
                .num_particles(100)
                .color(effectColor.intArray())
                .build());
            mapJson.addNode(Node.builder()
                .type(ExternalResource.POINT_LIGHT)
                .pos(roomEffectPosAbs.floatArray())
                .color(new Color(effectColor.r, effectColor.g, effectColor.b, 15).intArray())
                .positional_vibration(1.0f)
                .falloff(new Node.Falloff(
                    nextInt(250, 500),
                    nextInt(10, 20))
                ).build());
        });
        //endregion

        //region NODES: MAP TILES
        pointsRectArray(mapTilesCrop).forEach(mapTileIndex -> {
            final MapTile tile = mapTilesCrop[mapTileIndex.y][mapTileIndex.x];

            final String tileResourceId;
            if (tile.tileType == WALL) {
                tileResourceId = RESOURCE_WALL_ID;
            } else {
                tileResourceId = RESOURCE_FLOOR_ID;
            }

            // PATTERN
            final RoomStyle style = styles[tile.styleIndex];
            final int patternId = tile.tileType == WALL
                ? style.patternIdWall
                : style.patternIdFloor;
            if (patternId >= 0)
                mapJson.addTileNode(
                    "style" + tile.styleIndex + "_pattern_"
                        + (tile.tileType == WALL
                        ? "wall"
                        : "floor"),
                    mapTileIndex.x,
                    mapTileIndex.y,
                    0);

            // BASE
            mapJson.addTileNode(
                tileResourceId + tile.styleIndex,
                mapTileIndex.x,
                mapTileIndex.y,
                0);
        });
        //endregion

        //region NODES: CRATES
        pointsRectArray(rooms)
            .forEach(roomIndex -> {
                final Room room = rooms[roomIndex.y][roomIndex.x];
                if (roomIndex.x == 0 || roomIndex.y == 0) return;
                final Room roomLeft = rooms[roomIndex.y][roomIndex.x - 1];
                final Room roomUp = rooms[roomIndex.y - 1][roomIndex.x];
                final int roomLeftOffset = roomLeft.wallVert.offset + roomLeft.wallVert.width;
                final int roomUpOffset = roomUp.wallHoriz.offset + roomUp.wallHoriz.width;
                final Point startingRoomSpace = new Point(
                    room.roomSize.w + room.wallVert.offset - roomLeftOffset,
                    room.roomSize.h + room.wallHoriz.offset - roomUpOffset);
                final Size minSpaceLeft = new Size(
                    nextInt(config.cratesMinMaxSpaceLeftPerRoom.min.w, config.cratesMinMaxSpaceLeftPerRoom.max.w),
                    nextInt(config.cratesMinMaxSpaceLeftPerRoom.min.h, config.cratesMinMaxSpaceLeftPerRoom.max.h));

                Point currentSpaceLeft = startingRoomSpace;
                while (currentSpaceLeft.x >= minSpaceLeft.w
                    && currentSpaceLeft.y >= minSpaceLeft.h
                ) {
                    final boolean blocking = nextInt(0, 100) < config.crateBlockingChance;
                    currentSpaceLeft = currentSpaceLeft.add(new Point(-1, -1));
                    mapJson.addNode(Node.builder()
                        .type(RESOURCE_ID_PREFIX
                            + getCrateName.apply(
                            room.styleIndex,
                            nextInt(0, blocking ? config.cratesBlockingPerStyle : config.cratesNonBlockingPerStyle),
                            blocking)
                        ).rotation((float) nextInt(1, 359))
                        .pos(new Point(
                                (roomLeftOffset - diagonalRoomSizes[0].w + room.roomPosAbs.x + nextInt(0, startingRoomSpace.x))
                                    * TILE_SIZE.w
                                    + nextInt(-TILE_SIZE.w / 2, TILE_SIZE.w / 2),
                                (roomUpOffset - diagonalRoomSizes[0].h + room.roomPosAbs.y + nextInt(0, startingRoomSpace.y))
                                    * TILE_SIZE.h
                                    + nextInt(-TILE_SIZE.h / 2, TILE_SIZE.h / 2)
                            ).floatArray()
                        ).build());
                }
            });
        //endregion

        //region NODES: SPAWNS/BOMB SITES
        final Room spawnTRoom = rooms[rooms.length / 2 - 1][0];
        final Room spawnCTRoom = rooms[rooms.length / 2 - 1][rooms[0].length - 2];
        final Room siteARoom = rooms[0][rooms[0].length - 3];
        final Room siteBRoom = rooms[rooms.length - 2][rooms[0].length - 3];
        mapJson.addBombSiteA(
            siteARoom.roomPosAbs.x + siteARoom.roomSize.w / 2,
            siteARoom.roomPosAbs.y + siteARoom.roomSize.h / 2,
            siteARoom.roomSize.w,
            siteARoom.roomSize.h);
        mapJson.addBombSiteB(
            siteBRoom.roomPosAbs.x + siteBRoom.roomSize.w / 2,
            siteBRoom.roomPosAbs.y + siteBRoom.roomSize.h / 2,
            siteBRoom.roomSize.w,
            siteBRoom.roomSize.h);
        mapJson.addSpawnT(
            spawnTRoom.roomPosAbs.x + spawnTRoom.roomSize.w / 2,
            spawnTRoom.roomPosAbs.y + spawnTRoom.roomSize.h / 2,
            spawnTRoom.roomSize.w,
            spawnTRoom.roomSize.h);
        mapJson.addSpawnCT(
            spawnCTRoom.roomPosAbs.x + spawnCTRoom.roomSize.w / 2,
            spawnCTRoom.roomPosAbs.y + spawnCTRoom.roomSize.h / 2,
            spawnCTRoom.roomSize.w,
            spawnCTRoom.roomSize.h);
        //endregion

        //region WRITE JSON FILE
        final String mapJsonString = OBJECT_MAPPER
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(mapJson);
        final Path mapJsonFilePath = mapDirectory.resolve(config.mapName + ".json");
        System.out.println("Writing to " + mapJsonFilePath);
        Files.write(mapJsonFilePath, mapJsonString.getBytes());
        //endregion
        //endregion
    }

    @ToString
    @Getter
    @AllArgsConstructor
    public static final class MapTile {
        TileType tileType;
        Integer styleIndex;
        boolean carcass;
        boolean visible;
        Integer height;

        public static final MapTile VIRTUAL_TILE = new MapTile(
            WALL,
            0,
            false,
            false,
            0);

        public enum TileType {
            FLOOR,
            WALL,
            DOOR
        }
    }

    public static int nextInt(int from, int to) {
        return configRandom.randomEnabled
            ? baseRandom.nextInt(from, to)
            : (int) Math.floor((double) (from + to) / 2);
    }

    public static float nextFloat(float from, float to) {
        return configRandom.randomEnabled
            ? baseRandom.nextFloat(from, to)
            : (int) Math.floor((double) (from + to) / 2);
    }

    public static List<Point> pointsRectArray(Object[][] array) {
        return pointsRect(0, 0, array[0].length, array.length);
    }

    public static List<List<Point>> pointsRectArrayByRow(Object[][] array) {
        return pointsRectRows(array[0].length, array.length);
    }

    public static List<Point> pointsRect(int startX, int startY, int w, int h) {
        List<Point> points = new ArrayList<>();
        for (int iterY = startY; iterY < startY + h; iterY++) {
            for (int iterX = startX; iterX < startX + w; iterX++) {
                points.add(new Point(iterX, iterY));
            }
        }
        return points;
    }

    public static List<List<Point>> pointsRectRows(int w, int h) {
        List<List<Point>> points = new ArrayList<>();
        for (int iterY = 0; iterY < h; iterY++) {
            ArrayList<Point> points1 = new ArrayList<>();
            points.add(points1);
            for (int iterX = 0; iterX < w; iterX++) {
                points1.add(new Point(iterX, iterY));
            }
        }
        return points;
    }

    @Data
    @JsonDeserialize
    public static class ConfigurationRandom {
        boolean randomEnabled;
        long seed;
    }

    @Data
    @JsonDeserialize
    public static class Configuration {
        MinMaxColor roomLightMinMaxTint;
        String mapName;
        String gameDirectoryPath;
        boolean cropMap;
        Point roomsCount;
        MinMaxSize roomMinMaxSize;
        MinMaxSize wallMinMaxSize;
        Point wallMaxOffset;
        MinMaxSize doorMinMaxWidth;
        int styleCount;
        MinMaxSize styleSizeMinMaxSize;
        Color ambientLightColor;
        Color shadowTintFloor;
        Color shadowTintWall;
        Color blackLineFloorTint;
        Color blackLineWallTint;
        MinMaxColor floorMinMaxTintBase;
        MinMaxColor wallMinMaxTintBase;
        Color floorTintPerHeight;
        Color wallTintPerHeight;
        int wallHeight;
        int patternResourceCount;
        MinMaxColor patternMinMaxTintFloor;
        MinMaxColor patternMinMaxTintWall;
        String outputTextFilePath;
        String gameVersion;
        int crateBlockingChance;
        MinMaxSize cratesMinMaxSpaceLeftPerRoom;
        MinMaxColor crateBlockingMinMaxTint;
        MinMaxColor crateNonBlockingMinMaxTint;
        MinMaxSize crateMinMaxSize;
        int cratesNonBlockingPerStyle;
        int cratesBlockingPerStyle;

        public static int parseConfigEntry(String s) {
            final String[] split = s.split("_");
            if (split.length > 2) throw new IllegalArgumentException(s + ": split by _ length > 2");
            return split.length == 2
                ? nextInt(Integer.parseInt(split[0]), Integer.parseInt(split[1]))
                : Integer.parseInt(split[0]);
        }
    }

    @Value
    public static class ShadowCalcTileInfo {

        Entry left;
        Entry up;
        Entry right;
        Entry down;
        Entry upLeft;
        Entry upRight;
        Entry downLeft;
        Entry downRight;

        @Value
        public static class Entry {
            int hDif;
            boolean isWall;
            boolean sameStyle;
            boolean needLine;
            boolean higher;
        }
    }

    @Value
    @JsonDeserialize(using = Point.PointDeserializer.class)
    public static class Point {
        int x;
        int y;

        public static final Point LEFT = new Point(-1, 0);
        public static final Point RIGHT = new Point(1, 0);
        public static final Point UP = new Point(0, -1);
        public static final Point DOWN = new Point(0, 1);
        public static final Point UP_LEFT = new Point(-1, -1);
        public static final Point UP_RIGHT = new Point(1, -1);
        public static final Point DOWN_LEFT = new Point(-1, 1);
        public static final Point DOWN_RIGHT = new Point(1, 1);

        public Point add(Point other) {
            return new Point(x + other.x, y + other.y);
        }

        public Point mul(Point other) {
            return new Point(x * other.x, y * other.y);
        }

        public float[] floatArray() {
            return new float[]{(float) x, (float) y};
        }

        public static class PointDeserializer extends StdDeserializer<Point> {
            protected PointDeserializer() {
                super(Point.class);
            }

            @Override
            public Point deserialize(
                final JsonParser jsonParser,
                final DeserializationContext deserializationContext
            ) throws IOException, JacksonException {
                final String[] string = StringUtils.split(
                    StringUtils.replace(
                        jsonParser.getCodec().<JsonNode>readTree(jsonParser).asText(),
                        " ",
                        ""),
                    ",");
                return new Point(
                    parseConfigEntry(string[0]),
                    parseConfigEntry(string[1]));
            }
        }
    }

    @Value
    @JsonDeserialize(using = Size.SizeDeserializer.class)
    public static class Size {
        public static Size TILE_SIZE = new Size(128, 128);
        int w;
        int h;

        public float[] floatArray() {
            return new float[]{(float) w, (float) h};
        }

        public Point toPoint() {return new Point(w, h);}

        public static class SizeDeserializer extends StdDeserializer<Size> {
            protected SizeDeserializer() {
                super(Size.class);
            }

            @Override
            public Size deserialize(
                final JsonParser jsonParser,
                final DeserializationContext deserializationContext
            ) throws IOException, JacksonException {
                final String[] string = StringUtils.split(
                    StringUtils.replace(
                        jsonParser.getCodec().<JsonNode>readTree(jsonParser).asText(),
                        " ",
                        ""),
                    ",");
                return new Size(
                    parseConfigEntry(string[0]),
                    parseConfigEntry(string[1]));
            }
        }
    }

    @Value
    @JsonDeserialize(using = Color.ColorDeserializer.class)
    public static class Color {
        public static Color WHITE = new Color(255, 255, 255, 255);
        int r;
        int g;
        int b;
        int a;

        public Color add(Color other) {
            return new Color(
                r + other.r,
                g + other.g,
                b + other.b,
                a + other.a);
        }

        public Color mul(float mul) {
            return new Color(
                (int) (r * mul),
                (int) (g * mul),
                (int) (b * mul),
                (int) (a * mul));
        }

        public int[] intArray() {
            return new int[]{r, g, b, a};
        }

        public static class ColorDeserializer extends StdDeserializer<Color> {
            protected ColorDeserializer() {
                super(Color.class);
            }

            @Override
            public Color deserialize(
                final JsonParser jsonParser,
                final DeserializationContext deserializationContext
            ) throws IOException, JacksonException {
                final String[] string = StringUtils.split(
                    StringUtils.replace(
                        jsonParser.getCodec().<JsonNode>readTree(jsonParser).asText(),
                        " ",
                        ""),
                    ",");
                return new Color(
                    parseConfigEntry(string[0]),
                    parseConfigEntry(string[1]),
                    parseConfigEntry(string[2]),
                    parseConfigEntry(string[3]));
            }
        }
    }

    @Value
    @JsonDeserialize(using = MinMaxSize.MinMaxSizeDeserializer.class)
    public static class MinMaxSize {
        Size min;
        Size max;

        public Size randomize() {
            return new Size(
                nextInt(min.w, max.w),
                nextInt(min.h, max.h));
        }

        public static class MinMaxSizeDeserializer extends StdDeserializer<MinMaxSize> {
            public MinMaxSizeDeserializer() {
                super(MinMaxSize.class);
            }

            @Override
            public MinMaxSize deserialize(
                final JsonParser jsonParser,
                final DeserializationContext deserializationContext
            ) throws IOException, JacksonException {
                final String[] string = StringUtils.split(
                    jsonParser.getCodec().<JsonNode>readTree(jsonParser).asText(), ",");
                return new MinMaxSize(
                    new Size(Integer.parseInt(string[0]), Integer.parseInt(string[1])),
                    new Size(Integer.parseInt(string[2]) + 1, Integer.parseInt(string[3]) + 1));
            }
        }
    }

    @Value
    @JsonDeserialize(using = MinMaxColor.MinMaxColorDeserializer.class)
    public static class MinMaxColor {
        Color min;
        Color max;

        public Color randomize() {
            return new Color(
                nextInt(min.r, max.r),
                nextInt(min.g, max.g),
                nextInt(min.b, max.b),
                nextInt(min.a, max.a));
        }

        public static class MinMaxColorDeserializer extends StdDeserializer<MinMaxColor> {
            public MinMaxColorDeserializer() {
                super(MinMaxColor.class);
            }

            @Override
            public MinMaxColor deserialize(
                final JsonParser jsonParser,
                final DeserializationContext deserializationContext
            ) throws IOException, JacksonException {
                final String[] string = StringUtils.split(
                    jsonParser.getCodec().<JsonNode>readTree(jsonParser).asText(), ",");
                return new MinMaxColor(
                    new Color(
                        Integer.parseInt(string[0]),
                        Integer.parseInt(string[1]),
                        Integer.parseInt(string[2]),
                        Integer.parseInt(string[3])),
                    new Color(
                        Integer.parseInt(string[4]) + 1,
                        Integer.parseInt(string[5]) + 1,
                        Integer.parseInt(string[6]) + 1,
                        Integer.parseInt(string[7]) + 1));
            }
        }
    }

    @Value
    public static class Room {
        Point roomPosAbs;
        Size roomSize;
        Rect wallHoriz;
        Rect wallVert;
        Rect doorHoriz;
        Rect doorVert;
        int styleIndex;
        Size styleSize;

        public record Rect(
            int offset,
            int width) {}

        @Value
        public static class RoomStyle {
            int height;
            Color floorColor;
            Color wallColor;
            int patternIdFloor;
            int patternIdWall;
            Color patternColorFloor;
            Color patternColorWall;
        }
    }

    @Value
    public static class Map {
        Meta meta;
        About about;
        Settings settings;
        Playtesting playtesting;
        List<ExternalResource> external_resources;
        List<Layer> layers;
        List<Node> nodes;

        public void addNode(Node node) {
            nodes.add(node);
            layers.get(layers.size() - 1).nodes.add(node.id);
        }

        public void addTileNode(String type, int x, int y, int rot) {
            addNode(Node.builder()
                .type(RESOURCE_ID_PREFIX + type)
                .pos(new Point(x * 128, y * 128).floatArray())
                .rotation((float) rot)
                .build());
        }

        public void addAreaNode(String type, String letter, String faction, int x, int y, int w, int h) {
            addNode(Node.builder()
                .type(type)
                .pos(new Point(x * 128 + 64, y * 128 + 64).floatArray())
                .size(new Size(w * 128, h * 128).floatArray())
                .letter(letter)
                .faction(faction)
                .build());
        }

        public void addBlackLine(Point point, int rot, boolean isWall) {
            addTileNode(isWall
                ? LINE_WALL
                : LINE_FLOOR, point.x, point.y, rot);
        }

        public void addShadowCorner(Point point, int rot, int height, boolean isWall) {
            IntStream.range(0, height).forEach((i) ->
                addTileNode(isWall
                    ? SHADOW_WALL_CORNER
                    : SHADOW_FLOOR_CORNER, point.x, point.y, rot));
        }

        public void addShadowLine(Point point, int rot, int height, boolean isWall) {
            IntStream.range(0, Math.max(0, height)).forEach((i) ->
                addTileNode(isWall
                    ? SHADOW_WALL_LINE
                    : SHADOW_FLOOR_LINE, point.x, point.y, rot));
        }

        public void addBombSiteA(int x, int y, int w, int h) {
            addAreaNode(Node.TYPE_BOMBSITE, Node.LETTER_A, null, x, y, w, h);
        }

        public void addBombSiteB(int x, int y, int w, int h) {
            addAreaNode(Node.TYPE_BOMBSITE, Node.LETTER_B, null, x, y, w, h);
        }

        public void addSpawnT(int x, int y, int w, int h) {
            addAreaNode(Node.TYPE_TEAM_SPAWN, null, Node.FACTION_RESISTANCE, x, y, w, h);
            addAreaNode(Node.TYPE_BUY_ZONE, null, Node.FACTION_RESISTANCE, x, y, w, h);
        }

        public void addSpawnCT(int x, int y, int w, int h) {
            addAreaNode(Node.TYPE_TEAM_SPAWN, null, Node.FACTION_METROPOLIS, x, y, w, h);
            addAreaNode(Node.TYPE_BUY_ZONE, null, Node.FACTION_METROPOLIS, x, y, w, h);
        }
    }

    @Value
    public static class Meta {
        String game_version;
        String name;
        String version_timestamp;
    }

    @Value
    public static class About {
        String short_description;
    }

    @Value
    public static class Settings {
        String default_server_mode;
        int[] ambient_light_color;

        @Value
        public static class Playtesting {
            static final String QUICK_TEST = "quick_test";
            String mode;
        }

        @Value
        public static class Layer {
            String id;
            List<String> nodes;
        }

        @Value
        @Builder
        public static class Node {
            @Builder.Default
            String id = UUID.randomUUID().toString();
            String type;
            float[] pos;
            @Builder.Default
            float[] size = TILE_SIZE.floatArray();
            @Builder.Default
            Float rotation = 0.0f;
            String faction;
            String letter;
            Float positional_vibration;
            Float intensity_vibration;
            Falloff falloff;
            int[] color;
            int num_particles;

            static final String FACTION_RESISTANCE = "RESISTANCE";
            static final String TYPE_BOMBSITE = "bombsite";
            static final String LETTER_A = "A";
            static final String TYPE_BUY_ZONE = "buy_zone";
            static final String FACTION_METROPOLIS = "METROPOLIS";
            static final String TYPE_TEAM_SPAWN = "team_spawn";
            static final String LETTER_B = "B";

            @Value
            public static class Falloff {
                float radius;
                int strength;
            }

            @Builder
            @Value
            public static class ExternalResource {
                public static final String BASE_PNG_TEXTURE_FILENAME = "base";
                String path;
                @Builder.Default
                String file_hash = "03364891a7e8a89057d550d2816c8756c98e951524c4a14fa7e00981e0a46a62";
                String id;
                String domain;
                @Builder.Default
                float[] size = TILE_SIZE.floatArray();
                @Builder.Default
                int[] color = WHITE.intArray();
                AsPhysical as_physical;
                AsNonPhysical as_nonphysical;
                boolean stretch_when_resized;

                static final String DOMAIN_PHYSICAL = "PHYSICAL";
                static final String DOMAIN_FOREGROUND = "FOREGROUND";
                static final String RESOURCE_ID_PREFIX = "@";
                static final String PNG_EXT = ".png";
                static final String MAP_GFX_PATH = "gfx/";
                static final String RESOURCE_WALL_ID = "style_wall";
                static final String RESOURCE_FLOOR_ID = "style_floor";
                static final String ROOM_NOISE_CIRCLE = "room_noise_circle";
                static final String CRATE_NON_BLOCKING = "crate_non_blocking";
                static final String CRATE_BLOCKING = "crate_blocking";
                static final String SHADOW_WALL_CORNER = "shadow_wall_corner";
                static final String SHADOW_WALL_LINE = "shadow_wall_line";
                static final String SHADOW_FLOOR_LINE = "shadow_floor_line";
                static final String SHADOW_FLOOR_CORNER = "shadow_floor_corner";
                static final String LINE_FLOOR = "line_floor";
                static final String LINE_WALL = "line_wall";
                static final String WANDERING_PIXELS = "wandering_pixels";
                static final String POINT_LIGHT = "point_light";
            }

            @Value
            @Builder
            public static class AsPhysical {
                // lombok "'is' getter" fix
                @JsonProperty("is_static")
                @Getter(AccessLevel.NONE)
                boolean is_static;
                @JsonProperty("is_see_through")
                @Getter(AccessLevel.NONE)
                boolean is_see_through;
                @JsonProperty("is_throw_through")
                @Getter(AccessLevel.NONE)
                boolean is_throw_through;
                @JsonProperty("is_melee_throw_through")
                @Getter(AccessLevel.NONE)
                boolean is_melee_throw_through;
                @JsonProperty("is_shoot_through")
                @Getter(AccessLevel.NONE)
                boolean is_shoot_through;
                Float density; // 0.7
                Float friction; // 0.0
                Float bounciness; // 0.2
                Float penetrability; // 1
                Float collision_sound_sensitivity; // 1
                Float linear_damping; // 6.5
                Float angular_damping; // 6.5
                CustomShape custom_shape;

                static final AsPhysical AS_PHYSICAL_DEFAULT = AsPhysical.builder()
                    .is_static(true)
                    .build();

                @Value
                public static class CustomShape {
                    float[][] source_polygon;

                    static final CustomShape CRATE_SHAPE = new CustomShape(
                        new float[][]{
                            new float[]{-32.0f, -32.0f},
                            new float[]{32.0f, -32.0f},
                            new float[]{32.0f, 32.0f},
                            new float[]{-32.0f, 32.0f}});
                }
            }

            @Value
            public static class AsNonPhysical {
                boolean full_illumination;

                static final AsNonPhysical AS_NON_PHYSICAL_DEFAULT = new AsNonPhysical(true);
            }
        }
    }
}
