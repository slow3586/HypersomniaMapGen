package com.slow3586;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.Function1;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import org.jooq.lambda.Sneaky;
import com.slow3586.Main.Room.RoomStyle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import static com.slow3586.Main.MapTile.TileType.WALL;
import static com.slow3586.Main.Settings.*;
import static com.slow3586.Main.Settings.Node.AsNonPhysical.AS_NON_PHYSICAL;
import static com.slow3586.Main.Settings.Node.AsPhysical.AS_PHYSICAL;
import static com.slow3586.Main.Settings.Node.ExternalResource.DOMAIN_FOREGROUND;
import static com.slow3586.Main.Settings.Node.ExternalResource.DOMAIN_PHYSICAL;
import static com.slow3586.Main.Settings.Node.ExternalResource.RESOURCE_FLOOR_ID;
import static com.slow3586.Main.Settings.Node.ExternalResource.MAP_GFX_PATH;
import static com.slow3586.Main.Settings.Node.ExternalResource.RESOURCE_ID_PREFIX;
import static com.slow3586.Main.Settings.Node.ExternalResource.PNG_EXT;
import static com.slow3586.Main.Settings.Node.ExternalResource.RESOURCE_WALL_ID;
import static com.slow3586.Main.Size.TILE_SIZE;

public class Main {
    private static final MapTile VIRTUAL_TILE = new MapTile(
        WALL,
        0,
        false,
        false,
        0);
    public static final int WALL_HEIGHT = 4;
    private static boolean randomEnabled = true;
    private static Random random;

    public static void main(String[] args) throws IOException {
        //region GENERATION PARAMETERS
        random = new Random(129);
        final String mapName = "new_gen_test";
        final String mapDirectoryPath = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Hypersomnia\\user\\projects\\new_gen_test";
        final Path mapGfxPath = Paths.get(mapDirectoryPath, "gfx");
        final boolean cropMap = true;
        final Point roomsCountParam = new Point(6 + 1, 4 + 1);
        final MinMaxSize roomMinMaxSize = new MinMaxSize(
            new Size(5, 5),
            new Size(8, 8));
        final MinMaxSize wallMinMaxSize = new MinMaxSize(
            new Size(1, 1),
            new Size(3, 3));
        final Point wallMaxOffset = new Point(2, 2);
        final MinMaxSize doorMinMaxWidth = new MinMaxSize(
            new Size(2, 2),
            new Size(4, 4));
        final int styleCount = 3;
        final MinMaxSize styleSizeMinMaxSize = new MinMaxSize(
            new Size(0, 0),
            new Size(2, 2));
        final Color ambientLightColor = new Color(200, 150, 225, 225);
        final Color shadowTintFloor = new Color(255, 255, 255, 40);
        final Color shadowTintWall = new Color(255, 255, 255, 25);
        final Color blackLineFloorTint = new Color(255, 255, 255, 20);
        final Color blackLineWallTint = new Color(255, 255, 255, 30);
        final int floorTintBase = 110;
        final int wallTintBase = 210;
        final int floorTintChange = 15;
        final int wallTintChange = 20;
        //endregion

        //region GENERATION: ROOMS
        //region RANDOMIZE DIAGONAL ROOM SIZES
        final Size[] diagonalRoomSizes = Stream.generate(() -> new Size(
                nextInt(roomMinMaxSize.min.w, roomMinMaxSize.max.w),
                nextInt(roomMinMaxSize.min.h, roomMinMaxSize.max.h)))
            .limit(Math.max(roomsCountParam.x, roomsCountParam.y))
            .toArray(Size[]::new);
        //endregion

        //region RANDOMIZE ROOM STYLES
        final RoomStyle[] styles = IntStream.range(0, styleCount)
            .boxed()
            .map((i) -> new RoomStyle(
                i,
                new Color(
                    floorTintBase + floorTintChange * i,
                    floorTintBase + floorTintChange * i,
                    floorTintBase + floorTintChange * i,
                    255),
                new Color(
                    wallTintBase + wallTintChange * i,
                    wallTintBase + wallTintChange * i,
                    wallTintBase + wallTintChange * i,
                    255),
                nextInt(0, 11),
                nextInt(0, 11),
                new Color(255, 255, 255, nextInt(15, 25)),
                new Color(255, 255, 255, nextInt(15, 25)))
            ).toArray(RoomStyle[]::new);
        //endregion


        final Room[][] rooms = new Room[roomsCountParam.y][roomsCountParam.x];
        pointsRect(0, 0, roomsCountParam.x, roomsCountParam.y)
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
                    nextInt(wallMinMaxSize.min.w, wallMinMaxSize.max.w),
                    nextInt(wallMinMaxSize.min.h, wallMinMaxSize.max.h));
                final Point wallOffset = new Point(
                    -nextInt(0, Math.min(wallSize.w, wallMaxOffset.x)),
                    -nextInt(0, Math.min(wallSize.h, wallMaxOffset.y)));
                //endregion

                //region CALCULATE BASE ROOM SIZE
                final Size realRoomSize = new Size(
                    diagonalRoomSizes[roomIndex.x].w + wallOffset.x,
                    diagonalRoomSizes[roomIndex.y].h + wallOffset.y);
                //endregion

                //region RANDOMIZE DOOR
                final Size doorSize;
                final Point doorOffset;
                final boolean playableArea = roomIndex.x != 0 && roomIndex.y != 0;
                if (playableArea) {
                    doorSize = new Size(
                        roomIndex.y == rooms.length - 1
                            ? 0
                            : nextInt(doorMinMaxWidth.min.w,
                                Math.min(doorMinMaxWidth.max.w, realRoomSize.w)),
                        roomIndex.x == rooms[0].length - 1
                            ? 0
                            : nextInt(doorMinMaxWidth.min.h,
                                Math.min(doorMinMaxWidth.max.h, realRoomSize.h)));
                    doorOffset = new Point(
                        roomIndex.y == rooms.length - 1
                            ? 0
                            : nextInt(1, doorMinMaxWidth.min.w + realRoomSize.w - doorSize.w),
                        roomIndex.x == rooms[0].length - 1
                            ? 0
                            : nextInt(1, doorMinMaxWidth.min.h + realRoomSize.h - doorSize.h));
                } else {
                    doorSize = new Size(0, 0);
                    doorOffset = new Point(0, 0);
                }
                //endregion

                //region RANDOMIZE STYLE
                final int styleIndex = nextInt(0, 3);
                final Size styleSize = new Size(
                    roomIndex.x == rooms[0].length - 1
                        ? 1
                        : nextInt(styleSizeMinMaxSize.min.w, styleSizeMinMaxSize.max.w + 1),
                    roomIndex.y == rooms.length - 1
                        ? 1
                        : nextInt(styleSizeMinMaxSize.min.h, styleSizeMinMaxSize.max.h + 1));
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
                    playableArea,
                    styleIndex,
                    styleSize);
                //endregion
            });
        //endregion

        //region GENERATION: BASE MAP TILE ARRAY
        final MapTile[][] mapTilesUncrop =
            pointsRectRows(0, 0,
                Arrays.stream(diagonalRoomSizes)
                    .mapToInt(r -> r.w + wallMaxOffset.x)
                    .sum() + 1,
                Arrays.stream(diagonalRoomSizes)
                    .mapToInt(r -> r.h + wallMaxOffset.y)
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
                    mapTilesUncrop[pointAbs.y][pointAbs.x].tileType =
                        (mapTilesUncrop[pointAbs.y][pointAbs.x].tileType == MapTile.TileType.CONNECTOR)
                            || (pointAbs.x >= room.roomPosAbs.x + room.doorHoriz.offset
                            && pointAbs.x < room.roomPosAbs.x + room.doorHoriz.offset + room.doorHoriz.width)
                            ? MapTile.TileType.CONNECTOR
                            : WALL);
                //endregion

                //region WALL VERTICAL
                pointsRect(
                    room.roomPosAbs.x + room.roomSize.w + room.wallVert.offset,
                    room.roomPosAbs.y,
                    room.wallVert.width,
                    room.roomSize.h
                ).forEach(pointAbs ->
                    mapTilesUncrop[pointAbs.y][pointAbs.x].tileType =
                        (mapTilesUncrop[pointAbs.y][pointAbs.x].tileType == MapTile.TileType.CONNECTOR)
                            || (pointAbs.y >= room.roomPosAbs.y + room.doorVert.offset
                            && pointAbs.y < room.roomPosAbs.y + room.doorVert.offset + room.doorVert.width)
                            ? MapTile.TileType.CONNECTOR
                            : WALL);
                //endregion

                //region CARCASS HORIZONTAL
                pointsRect(
                    room.roomPosAbs.x,
                    room.roomPosAbs.y + room.roomSize.h,
                    room.roomSize.w,
                    1
                ).forEach(pointAbs ->
                    mapTilesUncrop[pointAbs.y][pointAbs.x].carcass = true);
                //endregion

                //region CARCASS VERTICAL
                pointsRect(
                    room.roomPosAbs.x + room.roomSize.w,
                    room.roomPosAbs.y,
                    1,
                    room.roomSize.h
                ).forEach(pointAbs ->
                    mapTilesUncrop[pointAbs.y][pointAbs.x].carcass = true);
                //endregion

                //region TILE ROOM TYPE
                pointsRect(
                    room.roomPosAbs.x,
                    room.roomPosAbs.y,
                    room.roomSize.w + room.styleSize.w,
                    room.roomSize.h + room.styleSize.h
                ).stream()
                    .map(pointAbs -> mapTilesUncrop[pointAbs.y][pointAbs.x])
                    .forEach(tile -> {
                        tile.styleIndex = room.styleIndex;
                        tile.height = styles[room.styleIndex].height
                            + (tile.tileType == WALL
                            ? WALL_HEIGHT
                            : 0);
                    });
                //endregion
                //endregion
            });
        //endregion

        //region GENERATION: CROP MAP
        final MapTile[][] mapTilesCrop;
        if (cropMap) {
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
                    mapTilesUncrop[y + diagonalRoomSizes[0].h],
                    diagonalRoomSizes[0].w,
                    diagonalRoomSizes[0].w + croppedMapSize.w);
            }

            mapTilesCrop = temp;
        } else {
            mapTilesCrop = mapTilesUncrop;
        }
        mapTilesCrop[mapTilesCrop.length - 1][mapTilesCrop[0].length - 1].tileType = WALL;
        //endregion

        //region GENERATION: FIX DIAGONAL WALLS TOUCH WITH EMPTY SIDES
        // #_    _#
        // _# OR #_
        final Function1<MapTile, Boolean> isFloor = (s) -> s.tileType == MapTile.TileType.FLOOR
            || s.tileType == MapTile.TileType.CONNECTOR;
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
                            mapTilesCrop[y][x].height -= WALL_HEIGHT;
                        mapTilesCrop[y][x].tileType = MapTile.TileType.FLOOR;
                        if (!isFloor.apply(mapTilesCrop[y][x + 1]))
                            mapTilesCrop[y][x + 1].height -= WALL_HEIGHT;
                        mapTilesCrop[y][x + 1].tileType = MapTile.TileType.FLOOR;
                        if (!isFloor.apply(mapTilesCrop[y + 1][x]))
                            mapTilesCrop[y + 1][x].height -= WALL_HEIGHT;
                        mapTilesCrop[y + 1][x].tileType = MapTile.TileType.FLOOR;
                        if (!isFloor.apply(mapTilesCrop[y + 1][x + 1]))
                            mapTilesCrop[y + 1][x + 1].height -= WALL_HEIGHT;
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
                    wallJoinerRow.append(mapTile.tileType == WALL
                        ? "#"
                        : mapTile.tileType == MapTile.TileType.CONNECTOR
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

        Files.write(Path.of("out.txt"), textJoiner.toString().getBytes());
        //endregion

        //region OUTPUT: CREATE MAP JSON FILE
        //region BASE MAP JSON OBJECT
        final Map mapJson = new Map(
            new Meta(
                "1.2.8546",
                mapName,
                "2023-11-14 17:28:36.619839 UTC"),
            new About("short desc"),
            new Settings(
                "bomb_defusal",
                ambientLightColor.intArray()),
            new Playtesting("quick_test"),
            new ArrayList<>(),
            List.of(new Layer(
                "default",
                new ArrayList<>())),
            new ArrayList<>());
        //endregion

        //region RESOURCES: FUNCTIONS
        final File texturesDir = new File("textures");
        final String basePngFilename = "base";
        if (!Files.exists(mapGfxPath)) {
            Files.createDirectory(mapGfxPath);
        }
        final BiConsumer<String, String> createTexture = Sneaky.biConsumer(
            (sourceFilename, targetFilename) -> {
                final Path sourcePath = texturesDir.toPath().resolve(sourceFilename + PNG_EXT);
                final Path targetPath = mapGfxPath.resolve(targetFilename + PNG_EXT);
                if (Files.exists(targetPath))
                    Files.delete(targetPath);
                Files.copy(sourcePath, targetPath);
            });
        //endregion

        //region RESOURCES: ROOMS
        mapJson.external_resources.add(
            Node.ExternalResource.builder()
                .path(MAP_GFX_PATH + "room_noise_circle" + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + "room_noise_circle")
                .stretch_when_resized(true)
                .domain(DOMAIN_FOREGROUND)
                .color(new Color(255, 255, 255, 150).intArray())
                .build());
        //endregion

        //region RESOURCES: STYLES
        for (int styleId = 0; styleId < styles.length; styleId++) {
            final RoomStyle style = styles[styleId];
            final String floorId = RESOURCE_FLOOR_ID + styleId;
            mapJson.external_resources.add(
                Node.ExternalResource.builder()
                    .path(MAP_GFX_PATH + floorId + PNG_EXT)
                    .id(RESOURCE_ID_PREFIX + floorId)
                    .color(style.floorColor.intArray())
                    .build());
            createTexture.accept(basePngFilename, floorId);

            final String wallId = RESOURCE_WALL_ID + styleId;
            mapJson.external_resources.add(
                Node.ExternalResource.builder()
                    .path(MAP_GFX_PATH + wallId + PNG_EXT)
                    .id(RESOURCE_ID_PREFIX + wallId)
                    .domain(DOMAIN_PHYSICAL)
                    .color(style.wallColor.intArray())
                    .as_physical(AS_PHYSICAL)
                    .build());
            createTexture.accept(basePngFilename, wallId);

            final String patternWallTarget = "style" + styleId + "_pattern_wall";
            mapJson.external_resources.add(
                Node.ExternalResource.builder()
                    .path(MAP_GFX_PATH + patternWallTarget + PNG_EXT)
                    .id(RESOURCE_ID_PREFIX + patternWallTarget)
                    .domain(DOMAIN_FOREGROUND)
                    .color(style.patternColorWall.intArray())
                    .build());
            createTexture.accept("pattern" + style.patternIdWall, patternWallTarget);

            final String patternFloorTarget = "style" + styleId + "_pattern_floor";
            mapJson.external_resources.add(
                Node.ExternalResource.builder()
                    .path(MAP_GFX_PATH + patternFloorTarget + PNG_EXT)
                    .id(RESOURCE_ID_PREFIX + patternFloorTarget)
                    .color(style.patternColorFloor.intArray())
                    .build());
            createTexture.accept("pattern" + style.patternIdFloor, patternFloorTarget);
        }
        //endregion

        //region SHADOWS 1: ADD SHADOW RESOURCES
        mapJson.external_resources.add(
            Node.ExternalResource.builder()
                .path(MAP_GFX_PATH + "shadow_wall_corner" + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + "shadow_wall_corner")
                .domain(DOMAIN_FOREGROUND)
                .color(shadowTintWall.intArray())
                .as_nonphysical(AS_NON_PHYSICAL)
                .build());

        mapJson.external_resources.add(
            Node.ExternalResource.builder()
                .path(MAP_GFX_PATH + "shadow_wall_line" + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + "shadow_wall_line")
                .domain(DOMAIN_FOREGROUND)
                .color(shadowTintWall.intArray())
                .as_nonphysical(AS_NON_PHYSICAL)
                .build());

        mapJson.external_resources.add(
            Node.ExternalResource.builder()
                .path(MAP_GFX_PATH + "shadow_floor_line" + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + "shadow_floor_line")
                .color(shadowTintFloor.intArray())
                .as_nonphysical(AS_NON_PHYSICAL)
                .build());

        mapJson.external_resources.add(
            Node.ExternalResource.builder()
                .path(MAP_GFX_PATH + "shadow_floor_corner" + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + "shadow_floor_corner")
                .color(shadowTintFloor.intArray())
                .as_nonphysical(AS_NON_PHYSICAL)
                .build());

        mapJson.external_resources.add(
            Node.ExternalResource.builder()
                .path(MAP_GFX_PATH + "line_floor" + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + "line_floor")
                .color(blackLineFloorTint.intArray())
                .as_nonphysical(AS_NON_PHYSICAL)
                .build());

        mapJson.external_resources.add(
            Node.ExternalResource.builder()
                .path(MAP_GFX_PATH + "line_wall" + PNG_EXT)
                .id(RESOURCE_ID_PREFIX + "line_wall")
                .color(blackLineWallTint.intArray())
                .as_nonphysical(AS_NON_PHYSICAL)
                .build());
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
                    ? VIRTUAL_TILE
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
            final Point pos;
            while (true) {
                final Point pos1 = new Point(
                    (room.roomPosAbs.x + nextInt(0, room.roomSize.h) - diagonalRoomSizes[0].w),
                    (room.roomPosAbs.y + nextInt(0, room.roomSize.h) - diagonalRoomSizes[0].h));
                final MapTile mapTile = mapTilesCrop[pos1.y][pos1.x];
                if (mapTile.visible && mapTile.tileType != WALL) {
                    pos = pos1.mul(TILE_SIZE.toPoint());
                    break;
                }
            }
            Color color = new Color(
                nextInt(20, 200),
                nextInt(20, 200),
                nextInt(20, 200),
                nextInt(20, 40)
            );
            float[] size = {
                room.roomSize.w * TILE_SIZE.w * (1 + (float) nextInt(1, 4) / 4),
                room.roomSize.h * TILE_SIZE.h * (1 + (float) nextInt(1, 4) / 4)
            };
            mapJson.addNode(Node.builder()
                .type(RESOURCE_ID_PREFIX + "room_noise_circle")
                .pos(pos.floatArray())
                .size(size)
                .rotation((float) nextInt(1, 359))
                .color(color.intArray())
                .build());
            mapJson.addNode(Node.builder()
                .type("wandering_pixels")
                .pos(pos.floatArray())
                .size(size)
                .num_particles(100)
                .color(color.intArray())
                .build());
            mapJson.addNode(Node.builder()
                .type("point_light")
                .pos(pos.floatArray())
                .color(new Color(color.r, color.g, color.b, 15).intArray())
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

        //region NODES: SPAWNS/BOMB SITES
        int spawnTOffset = 2;
        int spawnCTOffset = 2;
        int siteAOffset = 1;
        int siteBOffset = 3;

        mapJson.addBombSiteA(rooms[0].length - 2, siteAOffset, 3, 3);
        mapJson.addBombSiteB(rooms[0].length - 2, siteBOffset, 3, 3);
        mapJson.addSpawnT(0, spawnTOffset, 3, 3);
        mapJson.addSpawnCT(rooms[0].length - 1, spawnCTOffset, 3, 3);
        //endregion

        //region WRITE JSON FILE
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        final String mapJsonString = objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(mapJson);
        final Path mapJsonFilePath = Paths.get(mapDirectoryPath, mapName + ".json");
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

        public enum TileType {
            FLOOR,
            WALL,
            CONNECTOR
        }
    }

    public static int nextInt(int from, int to) {
        return randomEnabled
            ? random.nextInt(from, to)
            : (int) Math.floor((double) (from + to) / 2);
    }

    public static float nextFloat(float from, float to) {
        return randomEnabled
            ? random.nextFloat(from, to)
            : (int) Math.floor((double) (from + to) / 2);
    }

    public static List<Point> pointsRectArray(Object[][] array) {
        return pointsRect(0, 0, array[0].length, array.length);
    }

    public static List<List<Point>> pointsRectArrayByRow(Object[][] array) {
        return pointsRectRows(0, 0, array[0].length, array.length);
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

    public static List<List<Point>> pointsRectRows(int startX, int startY, int w, int h) {
        List<List<Point>> points = new ArrayList<>();
        for (int iterY = startY; iterY < startY + h; iterY++) {
            ArrayList<Point> points1 = new ArrayList<>();
            points.add(points1);
            for (int iterX = startX; iterX < startX + w; iterX++) {
                points1.add(new Point(iterX, iterY));
            }
        }
        return points;
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
    }

    @Value
    public static class Size {
        public static Size TILE_SIZE = new Size(128, 128);
        int w;
        int h;

        public float[] floatArray() {
            return new float[]{(float) w, (float) h};
        }

        public Point toPoint() {return new Point(w, h);}
    }

    @Value
    public static class Color {
        public static Color WHITE = new Color(255, 255, 255, 255);
        int r;
        int g;
        int b;
        int a;

        public int[] intArray() {
            return new int[]{r, g, b, a};
        }
    }

    @Value
    public static class MinMaxSize {
        Size min;
        Size max;
    }

    @Value
    public static class Room {
        Point roomPosAbs;
        Size roomSize;
        Rect wallHoriz;
        Rect wallVert;
        Rect doorHoriz;
        Rect doorVert;
        boolean playableArea;
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
        List<Node.ExternalResource> external_resources;
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
                ? "line_wall"
                : "line_floor", point.x, point.y, rot);
        }

        public void addShadowCorner(Point point, int rot, int height, boolean isWall) {
            IntStream.range(0, height).forEach((i) ->
                addTileNode(isWall
                    ? "shadow_wall_corner"
                    : "shadow_floor_corner", point.x, point.y, rot));
        }

        public void addShadowLine(Point point, int rot, int height, boolean isWall) {
            IntStream.range(0, Math.max(0, height)).forEach((i) ->
                addTileNode(isWall
                    ? "shadow_wall_line"
                    : "shadow_floor_line", point.x, point.y, rot));
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
                String path;
                @Builder.Default
                String file_hash = "03364891a7e8a89057d550d2816c8756c98e951524c4a14fa7e00981e0a46a62";
                String id;
                String domain;
                @Builder.Default
                float[] size = TILE_SIZE.floatArray();
                @Builder.Default
                int[] color = Color.WHITE.intArray();
                AsPhysical as_physical;
                AsNonPhysical as_nonphysical;
                boolean stretch_when_resized;

                static String DOMAIN_PHYSICAL = "PHYSICAL";
                static String DOMAIN_FOREGROUND = "FOREGROUND";
                static String RESOURCE_ID_PREFIX = "@";
                static String PNG_EXT = ".png";
                static String MAP_GFX_PATH = "gfx/";
                static String RESOURCE_WALL_ID = "style_wall";
                static String RESOURCE_FLOOR_ID = "style_floor";
            }

            @Value
            public static class AsPhysical {
                // lombok "'is' getter" fix
                @JsonProperty("is_static")
                @Getter(AccessLevel.NONE)
                boolean is_static;

                static AsPhysical AS_PHYSICAL = new AsPhysical(true);
            }

            @Value
            public static class AsNonPhysical {
                boolean full_illumination;

                static AsNonPhysical AS_NON_PHYSICAL = new AsNonPhysical(true);
            }
        }
    }
}
