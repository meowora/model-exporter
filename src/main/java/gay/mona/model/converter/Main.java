package gay.mona.model.converter;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.DataGenerator;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class Main {
    public static Path assetsDirectory;
    public static int assetVersion;

    public static void main(String[] args) throws IOException {
        SharedConstants.tryDetectVersion();
        OptionParser optionparser = new OptionParser();
        optionparser.allowsUnrecognizedOptions();
        var help = optionparser.accepts("help", "Show the help menu").forHelp();
        var output = optionparser.accepts("output", "Output folder").withRequiredArg().defaultsTo("out");
        var input = optionparser.accepts("input", "Input folder").withRequiredArg().defaultsTo("input");
        var assetDirectory = optionparser.accepts("assetsDir", "Assets Directory").withRequiredArg();
        var assetIndex  = optionparser.accepts("assetIndex", "").withRequiredArg().ofType(Integer.class);
        OptionSet optionset = optionparser.parse(args);

        if (!optionset.has(help)) {
            Path outputPath = Path.of(optionset.valueOf(output));
            Path inputPath = Path.of(optionset.valueOf(input));
            assetsDirectory = Path.of(optionset.valueOf(assetDirectory));
            assetVersion = optionset.valueOf(assetIndex);
            Bootstrap.bootStrap();
            ClientBootstrap.bootstrap();
            ModelConverter.bootstrap();
            DataGenerator dataGenerator = new DataGenerator(outputPath.resolve("_"), SharedConstants.getCurrentVersion(), true);
            try (var stream = Files.walk(inputPath)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".nbt")).map(path -> {
                    try {
                        var structure = new StructureTemplate();
                        structure.load(BuiltInRegistries.BLOCK, Objects.requireNonNull(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())));
                        return new Context(inputPath.relativize(path), structure);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).forEach(context -> {
                    var model =  ModelConverter.convertBlocksToJsonModel(context.template);
                    var outputFile = outputPath.resolve(context.fileName).resolveSibling(context.fileName.getFileName().toString().replace(".nbt", ".json"));
                    try {
                        Files.createDirectories(outputFile.getParent());
                        Files.writeString(outputFile, model.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            dataGenerator.run();
        } else {
            optionparser.printHelpOn(System.out);
        }
    }

    record Context(Path fileName, StructureTemplate template) {}
}
