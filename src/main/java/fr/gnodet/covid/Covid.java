package fr.gnodet.covid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class Covid {

    static final String[] DECES = new String[] {
            "https://static.data.gouv.fr/resources/fichier-des-personnes-decedees/20191209-192203/deces-2016.txt",
            "https://static.data.gouv.fr/resources/fichier-des-personnes-decedees/20191209-192304/deces-2017.txt",
            "https://static.data.gouv.fr/resources/fichier-des-personnes-decedees/20191205-191652/deces-2018.txt",
            "https://static.data.gouv.fr/resources/fichier-des-personnes-decedees/20200113-173945/deces-2019.txt",
            "https://static.data.gouv.fr/resources/fichier-des-personnes-decedees/20210112-143457/deces-2020.txt",
    };

    static final String[] PYRAM = new String[] {
            "https://www.insee.fr/fr/statistiques/fichier/1913143/pyramide-des-ages-2017.xls",
            "https://www.insee.fr/fr/statistiques/fichier/1913143/pyramide-des-ages-2018.xls",
            "https://www.insee.fr/fr/statistiques/fichier/1913143/pyramide-des-ages-2019.xls",
            "https://www.insee.fr/fr/statistiques/fichier/1913143/pyramide-des-ages-2020.xls",
    };

    public static void main(String[] args) throws Exception {

        Map<String, double[]> pyrams = new TreeMap<>();
        Stream.of(PYRAM).forEach(url -> loadPyrams(pyrams, url));

        // AAAAMM (deces) -> AAAA (naissance) -> nb
        Map<String, Map<String, Integer>> deces = new TreeMap<>();
        Stream.of(DECES).forEach(url -> loadDeaths(deces, url));

        // recherche autour du 01/01/2017 et du 01/01/2020
        extract(deces, pyrams, -5, 6, "deces.csv");

    }

    private static void loadPyrams(Map<String, double[]> pyrams, String url) {
        Path pyram = downloadAndCache(url);
        double[] ages = new double[101];
        try (Workbook workbook = new HSSFWorkbook(Files.newInputStream(pyram))) {
            Sheet sheet = workbook.getSheetAt(0);
            String name = sheet.getSheetName();
            for (int c = 0; c < ages.length; c++) {
                Cell cell = sheet.getRow(6 + c).getCell(4);
                ages[c] = cell.getNumericCellValue();
            }
            pyrams.put(name.substring(0, 4), ages);
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + url, e);
        }
    }

    private static void loadDeaths(Map<String, Map<String, Integer>> deces, String url) {
        Path path = downloadAndCache(url);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            int lines = 0;
            int blanks = 0;
            int unknownBirthYear = 0;
            int discarded = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                if (line.isBlank()) {
                    blanks++;
                    continue;
                }
                String dateNaissance = line.substring(81, 85);
                if (dateNaissance.equals("0000")) {
                    unknownBirthYear++;
                    continue;
                }

                int i = 154;
                String dateDeces = line.substring(i, i + 6);
                while (dateDeces.startsWith(" ")) {
                    i++;
                    dateDeces = line.substring(i, i + 6);
                }
                if (!dateDeces.matches("[12][90][0-9][0-9][0-1][0-9]")) {
                    discarded++;
                    continue;
                }

                deces.computeIfAbsent(dateDeces, k -> new HashMap<>())
                        .compute(dateNaissance, (k, v) -> v != null ? v + 1 : 1);
            }
            System.out.println("Read " + path.getFileName() + ": " + lines + " lines, "
                    + blanks + " blanks, " + unknownBirthYear + " invalid, "
                    + discarded + " discarded");
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + url, e);
        }
    }

    private static Path downloadAndCache(String urlStr) {
        try {
            /*
             *  fix for
             *    Exception in thread "main" javax.net.ssl.SSLHandshakeException:
             *       sun.security.validator.ValidatorException:
             *           PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
             *               unable to find valid certification path to requested target
             */
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {  }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {  }

                    }
            };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Create all-trusting host name verifier
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };
            // Install the all-trusting host verifier
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
            /*
             * end of the fix
             */

            URL url = new URL(urlStr);
            String fullPath = url.getPath();
            String fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
            Path path = Paths.get("target/data", fileName);
            if (Files.isRegularFile(path) && Files.isReadable(path) && Files.size(path) > 0) {
                return path;
            }
            Files.deleteIfExists(path);
            Path tmpPath = path.resolveSibling(fileName + ".tmp");
            Files.deleteIfExists(tmpPath);
            Files.createDirectories(tmpPath.getParent());
            System.out.println("Downloading " + fileName);
            try (InputStream is = url.openStream()) {
                Files.copy(is, tmpPath);
            }
            Files.move(tmpPath, path);
            return path;
        } catch (IOException | NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Error downloading " + urlStr, e);
        }
    }

    private static void extract(Map<String, Map<String, Integer>> deces,
                                Map<String, double[]> pyrams,
                                int min, int max, String name) throws IOException {
        NavigableMap<Integer, Integer> morts2017 = calculMortsParAge(deces, LocalDate.of(2017, 1, 1), min, max);
        NavigableMap<Integer, Integer> morts2018 = calculMortsParAge(deces, LocalDate.of(2018, 1, 1), min, max);
        NavigableMap<Integer, Integer> morts2019 = calculMortsParAge(deces, LocalDate.of(2019, 1, 1), min, max);
        NavigableMap<Integer, Integer> morts2020 = calculMortsParAge(deces, LocalDate.of(2020, 1, 1), min, max);
        try (Writer writer = Files.newBufferedWriter(Paths.get(name), StandardCharsets.ISO_8859_1)) {
            writer.write("Age;;Deces;;;;;Habitants;;;;;Mortalite (=deces/habitants*1000);;;;;Sur-mortalite\n");
            writer.write("Age;;2017;2018;2019;2020;;2017;2018;2019;2020;;2017;2018;2019;2020;;2018;2019;2020\n");
            for (int age = 0; age <= 100; age++) {
                writer.write(String.format("%d;;%d;%d;%d;%d;;%d;%d;%d;%d;;%s;%s;%s;%s;;%s;%s;%s%n",
                        age,
                        morts2017.getOrDefault(age, 0),
                        morts2018.getOrDefault(age, 0),
                        morts2019.getOrDefault(age, 0),
                        morts2020.getOrDefault(age, 0),
                        (int) pyrams.get("2017")[age],
                        (int) pyrams.get("2018")[age],
                        (int) pyrams.get("2019")[age],
                        (int) pyrams.get("2020")[age],
                        formatDouble(morts2017.getOrDefault(age, 0) / pyrams.get("2017")[age] * 1000.0),
                        formatDouble(morts2018.getOrDefault(age, 0) / pyrams.get("2018")[age] * 1000.0),
                        formatDouble(morts2019.getOrDefault(age, 0) / pyrams.get("2019")[age] * 1000.0),
                        formatDouble(morts2020.getOrDefault(age, 0) / pyrams.get("2020")[age] * 1000.0),
                        formatDouble(morts2018.getOrDefault(age, 0) / pyrams.get("2018")[age] * 1000.0 - morts2017.getOrDefault(age, 0) / pyrams.get("2017")[age] * 1000.0),
                        formatDouble(morts2019.getOrDefault(age, 0) / pyrams.get("2019")[age] * 1000.0 - morts2017.getOrDefault(age, 0) / pyrams.get("2017")[age] * 1000.0),
                        formatDouble(morts2020.getOrDefault(age, 0) / pyrams.get("2020")[age] * 1000.0 - morts2017.getOrDefault(age, 0) / pyrams.get("2017")[age] * 1000.0)
                        ));
            }
            writer.write(";\n");
            writer.write("Tranche;Surmortalite\n");
            for (int i = 0; i < 100; i += 5) {
                double morts17 = 0;
                double vivants17 = 0;
                double morts20 = 0;
                double vivants20 = 0;
                int m = i == 95 ? 100 : i + 4;
                for (int age = i; age <= m; age++) {
                    morts17 += morts2017.getOrDefault(age, 0);
                    morts20 += morts2020.getOrDefault(age, 0);
                    vivants17 += pyrams.get("2017")[age];
                    vivants20 += pyrams.get("2020")[age];
                }
                String tranche = (i == 95) ? "95+" : "'" + i + " - " + m;
                writer.write(tranche + ";" + formatDouble((morts20/vivants20-morts17/vivants17)*1000.0) + "\n");
            }
        }
    }

    private static String formatDouble(double v) {
        return String.format("%f", v).replace('.', ',');
    }

    static NavigableMap<Integer, Integer> calculMortsParAge(Map<String, Map<String, Integer>> deces, LocalDate pivot, int min, int max) {
        NavigableMap<Integer, Integer> map = new TreeMap<>();
        LongStream.range(min, max).mapToObj(pivot::plusMonths)
                .forEach(y -> {
                    String k = String.format("%04d%02d", y.getYear(), y.getMonthValue());
                    Map<String, Integer> decesParMois = deces.computeIfAbsent(k, x -> new HashMap<>());
                    for (Map.Entry<String, Integer> entry : decesParMois.entrySet()) {
                        int age = Math.min(100, y.getYear() - Integer.parseInt(entry.getKey()));
                        int nb = entry.getValue();
                        map.compute(age, (a, v) -> v != null ? v + nb : nb);
                    }
                });
        return map;
    }

}
