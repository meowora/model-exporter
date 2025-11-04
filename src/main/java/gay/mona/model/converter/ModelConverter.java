package gay.mona.model.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.math.MatrixUtil;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.block.model.multipart.MultiPartModel;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.BlockMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class ModelConverter {

    //private static ClientItemInfoLoader.LoadedClientInfos loadedClientInfos;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static Map<ResourceLocation, UnbakedModel> blockModels;
    private static BlockStateModelLoader.LoadedModels loadedModels;
    private static boolean isBootstrapped = false;

    public static void bootstrap() {
        if (isBootstrapped) {
            return;
        }

        var executor = CompletableFuture.delayedExecutor(0, TimeUnit.SECONDS);
        var directoryValidator = new DirectoryValidator((path) -> false);
        var clientPack = new ClientPackSource(Main.assetsDirectory, directoryValidator);
        var packRepository = new PackRepository(clientPack);
        packRepository.setSelected(packRepository.getAvailableIds());
        var vanilla = ClientPackSource.createVanillaPackSource(Main.assetsDirectory);
        var resourceManager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(vanilla));
        blockModels = ModelManager.loadBlockModels(resourceManager, executor).join();
        loadedModels = BlockStateModelLoader.loadBlockStates(resourceManager, executor).join();
        //loadedClientInfos = ClientItemInfoLoader.scheduleLoad(resourceManager, executor).join();
        isBootstrapped = true;
    }

    public static void assertBootstrapped() {
        if (!isBootstrapped) {
            throw new UnsupportedOperationException("Not bootstrapped yet!");
        }
    }


    public static JsonObject convertBlocksToJsonModel(StructureTemplate template) {
        assertBootstrapped();
        JsonObject jsonObject = new JsonObject();
        BlockPos.MutableBlockPos neighborPosition = new BlockPos.MutableBlockPos();

        JsonArray elements = new JsonArray();
        Map<ResourceLocation, String> textureReferences = new HashMap<>();
        var boundingBox = template.getBoundingBox(BlockPos.ZERO, Rotation.NONE, BlockPos.ZERO, Mirror.NONE);
        var max = new BlockPos(boundingBox.maxX(), boundingBox.maxY(), boundingBox.maxZ());
        var min = new BlockPos(boundingBox.minX(), boundingBox.minY(), boundingBox.minZ());
        float scaleX = 1.0F / (float) (max.getX() + 1 - min.getX());
        float scaleY = 1.0F / (float) (max.getY() + 1 - min.getY());
        float scaleZ = 1.0F / (float) (max.getZ() + 1 - min.getZ());
        float globalScale = Math.min(scaleX, Math.min(scaleY, scaleZ));
        float centeringX = 8.0F - (float) (max.getX() + 1 + min.getX()) * globalScale * 8.0F;
        float centeringY = 8.0F - (float) (max.getY() + 1 + min.getY()) * globalScale * 8.0F;
        float centeringZ = 8.0F - (float) (max.getZ() + 1 + min.getZ()) * globalScale * 8.0F;
        var centeringVec = new Vector3f(centeringX, centeringY, centeringZ);

        var pallet = template.palettes.getFirst();
        var blockInfoMatrix = new StructureTemplate.StructureBlockInfo[max.getX() + 1][max.getY() + 1][max.getZ() + 1];
        for (var block : pallet.blocks()) {
            var pos = block.pos();
            blockInfoMatrix[pos.getX()][pos.getY()][pos.getZ()] = block;
        }

        for (var blockPos : BlockPos.betweenClosed(0, 0, 0, max.getX(), max.getY(), max.getZ())) {
            var blockInfo = blockInfoMatrix[blockPos.getX()][blockPos.getY()][blockPos.getZ()];
            if (blockInfo == null) {
                continue;
            }
            var blockPosVector = new Vector3f(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            var state = blockInfo.state();

            if (!state.isAir() && state.getRenderShape() != RenderShape.INVISIBLE) {
                var culledDirections = new ArrayList<Direction>();
                for (Direction direction : DIRECTIONS) {
                    neighborPosition.setWithOffset(blockPos, direction);
                    var neighbour = getPos(blockInfoMatrix, neighborPosition);
                    if (neighbour == null || Block.shouldRenderFace(state, neighbour.state(), direction)) {
                        continue;
                    }
                    culledDirections.add(direction);
                }

                var randomSource = RandomSource.create(blockPos.asLong());
                BlockStateModel.UnbakedRoot models = loadedModels.models().get(state);
                List<SingleVariant.Unbaked> unbaked = new ArrayList<>();
                switch (models) {
                    case BlockStateModel.SimpleCachedUnbakedRoot simple ->
                            resolveVariants(simple.contents, unbaked, randomSource);
                    case MultiPartModel.Unbaked multiPart -> multiPart.selectors.stream()
                            .filter(selector -> selector.condition().test(state))
                            .map(MultiPartModel.Selector::model)
                            .forEach(part -> resolveVariants(part, unbaked, randomSource));
                    default -> {
                        System.out.println(models);
                        continue;
                    }
                }

                for (var unbakedVariant : unbaked) {
                    var variant = unbakedVariant.variant();
                    var modelState = variant.modelState();
                    var model = resolve(blockModels, variant.modelLocation());

                    var geometry = model.simpleGeometry();
                    if (geometry == null) {
                        continue;
                    }

                    var blockModelState = modelState.asModelState();
                    for (var element : geometry.elements()) {
                        var transformMatrix = blockModelState.transformation().getMatrix();
                        Vector3f fromCopy = new Vector3f(element.from());
                        Vector3f toCopy = new Vector3f(element.to());
                        Vector3f transPivot = new Vector3f(8f, 8f, 8f);
                        fromCopy.sub(transPivot);
                        toCopy.sub(transPivot);
                        transformMatrix.transformPosition(fromCopy);
                        transformMatrix.transformPosition(toCopy);
                        fromCopy.add(transPivot);
                        toCopy.add(transPivot);

                        if (fromCopy.x > toCopy.x != element.from().x() > element.to().x()) {
                            float temp = fromCopy.x;
                            fromCopy.x = toCopy.x;
                            toCopy.x = temp;
                        }

                        if (fromCopy.y > toCopy.y != element.from().y() > element.to().y()) {
                            float temp = fromCopy.y;
                            fromCopy.y = toCopy.y;
                            toCopy.y = temp;
                        }

                        if (fromCopy.z > toCopy.z != element.from().z() > element.to().z()) {
                            float temp = fromCopy.z;
                            fromCopy.z = toCopy.z;
                            toCopy.z = temp;
                        }

                        var elementObject = new JsonObject();
                        var scaledBlockPos = blockPosVector.mul(16, new Vector3f());
                        var finalFrom = fromCopy.add(scaledBlockPos, new Vector3f()).mul(globalScale).add(centeringVec);
                        var from = serialize(finalFrom);
                        var finalTo = toCopy.add(scaledBlockPos, new Vector3f()).mul(globalScale).add(centeringVec);
                        var to = serialize(finalTo);

                        elementObject.add("from", from);
                        elementObject.add("to", to);
                        if (element.rotation() != null) {
                            var rotation = element.rotation();
                            var rotationDirection = Direction.get(Direction.AxisDirection.POSITIVE, rotation.axis());
                            var actualRotationDirection = Direction.rotate(transformMatrix, rotationDirection);
                            final BlockElementRotation actualRotation;
                            if (rotationDirection == actualRotationDirection) {
                                actualRotation = rotation;
                            } else {
                                actualRotation = new BlockElementRotation(
                                        rotation.origin(),
                                        rotationDirection.getAxis(),
                                        rotation.angle(),
                                        rotation.rescale());
                            }

                            var rotationObject = new JsonObject();
                            var origin = actualRotation.origin()
                                    .add(scaledBlockPos, new Vector3f())
                                    .add(transPivot)
                                    .mul(globalScale)
                                    .add(centeringVec);
                            rotationObject.add("origin", serialize(origin));
                            rotationObject.addProperty("axis", actualRotation.axis().getSerializedName());
                            rotationObject.addProperty("angle", actualRotation.angle());
                            if (actualRotation.rescale()) {
                                rotationObject.addProperty("rescale", true);
                            }

                            elementObject.add("rotation", rotationObject);
                        }
                        elementObject.addProperty("name", blockPos.toShortString() + " - " + variant.modelLocation());

                        var faces = new JsonObject();
                        element.faces().forEach((direction, face) -> {
                            if (face.cullForDirection() != null) {
                                var rotated = Direction.rotate(transformMatrix, face.cullForDirection());
                                var shouldCull = culledDirections.contains(rotated);
                                if (shouldCull) {
                                    return;
                                }
                            }
                            var faceObject = new JsonObject();

                            var transformedDirection = Direction.rotate(transformMatrix, direction);
                            final BlockElementFace.UVs uvs;
                            if (face.uvs() != null) {
                                uvs = face.uvs();
                            } else {
                                uvs = FaceBakery.defaultFaceUV(fromCopy, toCopy, transformedDirection);
                            }

                            var uv = new JsonArray();

                            var rotation = face.rotation();
                            if (modelState.uvLock()) {
                                var firstVertex = rotation.rotateVertexIndex(0);
                                var secondVertex = rotation.rotateVertexIndex(2);
                                var transformationMatrix = blockModelState.inverseFaceTransformation(transformedDirection);

                                var startUv = new Vector3f(uvs.getVertexU(firstVertex), uvs.getVertexV(firstVertex), 0);
                                var endUv = new Vector3f(uvs.getVertexU(secondVertex), uvs.getVertexV(secondVertex), 0);

                                var transUv1 = new Vector3f(startUv);
                                var transUv2 = new Vector3f(endUv);

                                if (!MatrixUtil.isIdentity(transformationMatrix)) {
                                    var uvOffset = new Vector3f(8, 8, 0);
                                    transUv1.sub(uvOffset);
                                    transUv2.sub(uvOffset);
                                    transformationMatrix.transformPosition(transUv1);
                                    transformationMatrix.transformPosition(transUv2);
                                    transUv1.add(uvOffset);
                                    transUv2.add(uvOffset);
                                }

                                var mirrorX = Math.signum(endUv.x - startUv.x) == Math.signum(transUv2.x - transUv1.x);
                                var mirrorY = Math.signum(endUv.y - startUv.y) == Math.signum(transUv2.y - transUv1.y);

                                uv.add(mirrorX ? transUv1.x : transUv2.x);
                                uv.add(mirrorY ? transUv1.y : transUv2.y);
                                uv.add(mirrorX ? transUv2.x : transUv1.x);
                                uv.add(mirrorY ? transUv2.y : transUv1.y);

                                faceObject.addProperty("rotation", rotation.shift * 90);
                            } else {
                                var global = BlockMath.VANILLA_UV_TRANSFORM_LOCAL_TO_GLOBAL.get(direction);
                                var local = BlockMath.VANILLA_UV_TRANSFORM_GLOBAL_TO_LOCAL.get(transformedDirection);
                                var rotationMatrix = new Matrix4f()
                                        .translation(0.5F, 0.5F, 0.5F)
                                        .mul(global.getMatrixCopy()
                                                .mul(blockModelState.transformation().getMatrix())
                                                .mul(local.getMatrix())
                                        )
                                        .translate(-0.5F, -0.5F, -0.5F);

                                float radians = rotation.shift * 90f * Mth.DEG_TO_RAD;
                                var vector3f = new Matrix3f(rotationMatrix)
                                        .transform(Mth.cos(radians), Mth.sin(radians), 0.0f, new Vector3f());

                                uv.add(uvs.minU());
                                uv.add(uvs.minV());
                                uv.add(uvs.maxU());
                                uv.add(uvs.maxV());

                                faceObject.addProperty("rotation", Mth.wrapDegrees(
                                        Mth.roundToward((int) Math.toDegrees(Math.atan2(vector3f.y(), vector3f.x())), 90)
                                ));
                            }

                            faceObject.add("uv", uv);

                            faceObject.addProperty("texture", getOrCreateKey(textureReferences, model, face.texture()));
                            faces.add(transformedDirection.getSerializedName(), faceObject);
                        });
                        if (faces.isEmpty()) {
                            continue;
                        }
                        elementObject.add("faces", faces);
                        elements.add(elementObject);
                    }
                }
            }
        }

        jsonObject.add("elements", elements);
        JsonObject textures = new JsonObject();

        for (Map.Entry<ResourceLocation, String> entry : textureReferences.entrySet()) {
            ResourceLocation resourceLocation = entry.getKey();
            if (resourceLocation == null) {
                textures.addProperty(entry.getValue(), "");
            } else if (resourceLocation.getNamespace().equals("minecraft")) {
                textures.addProperty(entry.getValue(), entry.getKey().getPath());
            } else {
                textures.addProperty(entry.getValue(), entry.getKey().toString());
            }
        }

        jsonObject.add("textures", textures);
        return jsonObject;
    }

    private static JsonArray serialize(Vector3f vector3f) {
        var jsonArray = new JsonArray();
        jsonArray.add(vector3f.x);
        jsonArray.add(vector3f.y);
        jsonArray.add(vector3f.z);
        return jsonArray;
    }

    private static void resolveVariants(
            BlockStateModel.Unbaked model,
            List<SingleVariant.Unbaked> variants,
            RandomSource randomSource
    ) {
        if (model instanceof WeightedVariants.Unbaked(WeightedList<BlockStateModel.Unbaked> entries)) {
            resolveVariants(entries.getRandomOrThrow(randomSource), variants, randomSource);
        } else if (model instanceof SingleVariant.Unbaked singleVariant) {
            variants.add(singleVariant);
        }
    }

    @Nullable
    private static <T> T getPos(@Nullable T @NotNull [] @NotNull [] @NotNull [] matrix, BlockPos position) {
        try {
            return matrix[position.getX()][position.getY()][position.getZ()];
        } catch (IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    public static Transformation getUVTransform(Transformation transformation, Direction direction) {
        Direction direction2 = Direction.rotate(transformation.getMatrix(), direction);
        Transformation transformation3 = BlockMath.VANILLA_UV_TRANSFORM_LOCAL_TO_GLOBAL
                .get(direction)
                .compose(transformation)
                .compose(BlockMath.VANILLA_UV_TRANSFORM_GLOBAL_TO_LOCAL.get(direction2));
        return BlockMath.blockCenterToCorner(transformation3);
    }

    private static String getOrCreateKey(
            Map<ResourceLocation, String> textureMap,
            ResolvedBlockModel model,
            String textureKey
    ) {
        var data = model.textures.get(textureKey.substring(textureKey.indexOf("#") + 1));

        ResourceLocation textureLocation;
        switch (data) {
            case null -> textureLocation = null;
            case TextureSlots.Reference reference -> {
                return getOrCreateKey(textureMap, model, reference.target());
            }
            case TextureSlots.Value value -> textureLocation = value.material().texture();
        }

        return textureMap.computeIfAbsent(textureLocation, key -> Integer.toString(textureMap.size()));
    }

    static ResolvedBlockModel resolve(
            Map<ResourceLocation, UnbakedModel> models,
            ResourceLocation resourceLocation
    ) {
        var model = models.get(resourceLocation);
        final ResolvedBlockModel parent;
        if (model.parent() != null) {
            parent = resolve(models, model.parent());
        } else {
            parent = null;
        }

        return new ResolvedBlockModel(parent, model, new HashMap<>());
    }

    record ResolvedBlockModel(
            @Nullable ResolvedBlockModel resolvedParent,
            UnbakedModel model,
            HashMap<String, TextureSlots.SlotContents> textures
    ) implements UnbakedModel {

        ResolvedBlockModel {
            if (resolvedParent != null) {
                textures.putAll(resolvedParent.textureSlots().values());
            }
            textures.putAll(model.textureSlots().values());
        }

        @Override
        public @Nullable Boolean ambientOcclusion() {
            return get(UnbakedModel::ambientOcclusion);
        }

        @Override
        public @Nullable GuiLight guiLight() {
            return get(UnbakedModel::guiLight);
        }

        @Override
        public @NotNull TextureSlots.Data textureSlots() {
            return new TextureSlots.Data(textures);
        }

        @Override
        public @Nullable UnbakedGeometry geometry() {
            return get(UnbakedModel::geometry);
        }

        public SimpleUnbakedGeometry simpleGeometry() {
            return (SimpleUnbakedGeometry) geometry();
        }

        @Override
        public @Nullable ResourceLocation parent() {
            return model.parent();
        }

        public <T> T get(Function<UnbakedModel, T> transform) {
            var value = transform.apply(model);
            if (value != null) {
                return value;
            }
            return resolvedParent != null ? transform.apply(resolvedParent) : null;
        }

    }
}
