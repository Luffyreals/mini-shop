package com.minishop.config;

import com.minishop.catalog.CategoryRepository;
import com.minishop.catalog.Product;
import com.minishop.catalog.ProductRepository;
import com.minishop.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-009-15 (data) and QĐ9: the 21 seed images, their credits and the {@code image_path} the seed writes agree.
 * The resource name on the classpath is exactly the path stored in {@code products.image_path}, so no migration or
 * mapping is needed and {@code getResource(imagePath)} is enough.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DataInitializer.class, PasswordEncoderConfig.class})
@EnableConfigurationProperties(SeedProperties.class)
class SeedImagesTest {

    private static final String IMAGES_DIRECTORY = "images/products/";
    private static final String CREDITS = "images/credits.tsv";
    private static final int COLUMNS = 9;
    private static final int IMAGE_COUNT = 21;

    @Autowired
    DataInitializer initializer;
    @Autowired
    CategoryRepository categories;
    @Autowired
    ProductRepository products;
    @Autowired
    UserRepository users;

    @BeforeEach
    void emptyDatabase() {
        products.deleteAllInBatch();
        categories.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ the seed

    @Test
    void seededImagePaths_areTwentyOneDistinctPathsAndEachResolvesToAResource() {
        initializer.run(new DefaultApplicationArguments());

        List<String> paths = products.findAll().stream().map(Product::getImagePath)
                .filter(path -> path != null).toList();
        assertThat(paths).hasSize(IMAGE_COUNT).doesNotHaveDuplicates();
        for (String path : paths) {
            assertThat(path).as("image_path").startsWith("/" + IMAGES_DIRECTORY).endsWith(".jpg");
            assertThat(new ClassPathResource(path).exists()).as("classpath resource %s", path).isTrue();
            assertThat(SeedImagesTest.class.getResource(path)).as("getResource(%s)", path).isNotNull();
        }
    }

    @Test
    void seededImagePaths_matchTheFilesAndTheCreditsExactly() throws IOException {
        initializer.run(new DefaultApplicationArguments());

        Set<String> fromSeed = new TreeSet<>();
        for (Product product : products.findAll()) {
            if (product.getImagePath() != null) {
                fromSeed.add(product.getImagePath().substring(("/" + IMAGES_DIRECTORY).length()));
            }
        }

        assertThat(fromSeed).isEqualTo(imageFiles()).isEqualTo(new TreeSet<>(creditedFiles()));
    }

    // ------------------------------------------------------------------ the files and the credits

    @Test
    void imageFolder_hasTwentyOneFilesAndEachIsAJpegWithContent() throws IOException {
        Set<String> files = imageFiles();

        assertThat(files).hasSize(IMAGE_COUNT).allMatch(name -> name.endsWith(".jpg"));
        for (String file : files) {
            try (var in = new ClassPathResource(IMAGES_DIRECTORY + file).getInputStream()) {
                byte[] header = in.readNBytes(4);
                assertThat(header).as("JPEG magic bytes of %s", file)
                        .startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
            }
            assertThat(new ClassPathResource(IMAGES_DIRECTORY + file).contentLength())
                    .as("size of %s", file).isGreaterThan(1_000L);
        }
    }

    @Test
    void credits_haveOneImageRowPerFileWithoutDuplicates() throws IOException {
        List<String> credited = creditedFiles();

        assertThat(credited).hasSize(IMAGE_COUNT).doesNotHaveDuplicates();
        assertThat(new TreeSet<>(credited)).isEqualTo(imageFiles());
    }

    @Test
    void credits_everyRowHasNineColumnsAndTheRequiredFields() throws IOException {
        List<String[]> rows = creditRows();

        assertThat(rows).hasSize(IMAGE_COUNT);
        for (String[] row : rows) {
            String file = row[1];
            assertThat(row).as("columns of %s", file).hasSize(COLUMNS);
            assertThat(row[0]).as("kind of %s", file).isEqualTo("image");
            for (int column : new int[]{1, 2, 3, 4, 5, 7}) {
                assertThat(row[column]).as("column %d of %s", column, file).isNotBlank();
            }
        }
    }

    @Test
    void credits_ccByAndCcBySaRowsHaveAnAuthorAndLicenseUrl() throws IOException {
        List<String[]> attributionRows = creditRows().stream()
                .filter(row -> row[5].startsWith("CC BY")).toList();

        assertThat(attributionRows).isNotEmpty();
        for (String[] row : attributionRows) {
            assertThat(row[4]).as("author of %s", row[1]).isNotBlank().isNotEqualTo("Không ghi tác giả");
            assertThat(row[6]).as("license_url of %s", row[1]).startsWith("https://creativecommons.org/");
            assertThat(row[8]).as("source_url of %s", row[1]).startsWith("https://");
        }
    }

    @Test
    void credits_fileStartsWithTheIntroductionAndHeader() throws IOException {
        List<String> lines = readLines();

        assertThat(lines.getFirst()).startsWith("#");
        List<String> data = lines.stream().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
        assertThat(data.getFirst().split("\t", -1)).containsExactly(
                "kind", "file", "subject", "title", "author", "license", "license_url", "source", "source_url");
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> imageFiles() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:" + IMAGES_DIRECTORY + "*");
        Set<String> names = new TreeSet<>();
        Arrays.stream(resources).forEach(resource -> names.add(resource.getFilename()));
        return names;
    }

    private static List<String> creditedFiles() throws IOException {
        return creditRows().stream().map(row -> row[1]).toList();
    }

    /** The {@code image} rows: no {@code #} lines, the first other line is the header, split on tabs. */
    private static List<String[]> creditRows() throws IOException {
        List<String> data = readLines().stream().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
        List<String[]> rows = new ArrayList<>();
        for (String line : data.subList(1, data.size())) {
            String[] columns = line.split("\t", -1);
            if (columns.length > 0 && columns[0].equals("image")) {
                rows.add(columns);
            }
        }
        return rows;
    }

    private static List<String> readLines() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(CREDITS).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }
}
