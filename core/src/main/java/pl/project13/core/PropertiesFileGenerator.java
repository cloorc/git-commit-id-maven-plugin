/*
 * This file is part of git-commit-id-plugin by Konrad 'ktoso' Malawski <konrad.malawski@java.pl>
 *
 * git-commit-id-plugin is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * git-commit-id-plugin is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with git-commit-id-plugin.  If not, see <http://www.gnu.org/licenses/>.
 */

package pl.project13.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sonatype.plexus.build.incremental.BuildContext;
import pl.project13.core.log.LoggerBridge;
import pl.project13.core.util.SortedProperties;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class PropertiesFileGenerator {

  private LoggerBridge log;
  private BuildContext buildContext;
  private String format;
  private String prefixDot;
  private String projectName;

  public PropertiesFileGenerator(LoggerBridge log, BuildContext buildContext, String format, String prefixDot, String projectName) {
    this.log = log;
    this.buildContext = buildContext;
    this.format = format;
    this.prefixDot = prefixDot;
    this.projectName = projectName;
  }

  public void maybeGeneratePropertiesFile(@Nonnull Properties localProperties, File base, String propertiesFilename, Charset sourceCharset) throws GitCommitIdExecutionException {
    try {
      final File gitPropsFile = craftPropertiesOutputFile(base, propertiesFilename);
      final boolean isJsonFormat = "json".equalsIgnoreCase(format);

      boolean shouldGenerate = true;

      if (gitPropsFile.exists()) {
        final Properties persistedProperties;

        try {
          if (isJsonFormat) {
            log.info("Reading existing json file [{}] (for module {})...", gitPropsFile.getAbsolutePath(), projectName);

            persistedProperties = readJsonProperties(gitPropsFile, sourceCharset);
          } else {
            log.info("Reading existing properties file [{}] (for module {})...", gitPropsFile.getAbsolutePath(), projectName);

            persistedProperties = readProperties(gitPropsFile, sourceCharset);
          }

          final Properties propertiesCopy = (Properties) localProperties.clone();

          final String buildTimeProperty = prefixDot + GitCommitPropertyConstant.BUILD_TIME;

          propertiesCopy.remove(buildTimeProperty);
          persistedProperties.remove(buildTimeProperty);

          shouldGenerate = !propertiesCopy.equals(persistedProperties);
        } catch (CannotReadFileException ex) {
          // Read has failed, regenerate file
          log.info("Cannot read properties file [{}] (for module {})...", gitPropsFile.getAbsolutePath(), projectName);
          shouldGenerate = true;
        }
      }

      if (shouldGenerate) {
        Files.createDirectories(gitPropsFile.getParentFile().toPath());
        try (OutputStream outputStream = new FileOutputStream(gitPropsFile)) {
          SortedProperties sortedLocalProperties = new SortedProperties();
          sortedLocalProperties.putAll(localProperties);
          if (isJsonFormat) {
            try (Writer outputWriter = new OutputStreamWriter(outputStream, sourceCharset)) {
              log.info("Writing json file to [{}] (for module {})...", gitPropsFile.getAbsolutePath(), projectName);
              ObjectMapper mapper = new ObjectMapper();
              mapper.writerWithDefaultPrettyPrinter().writeValue(outputWriter, sortedLocalProperties);
            }
          } else {
            log.info("Writing properties file to [{}] (for module {})...", gitPropsFile.getAbsolutePath(), projectName);
            // using outputStream directly instead of outputWriter this way the UTF-8 characters appears in unicode escaped form
            sortedLocalProperties.store(outputStream, "Generated by Git-Commit-Id-Plugin");
          }
        } catch (final IOException ex) {
          throw new RuntimeException("Cannot create custom git properties file: " + gitPropsFile, ex);
        }

        if (buildContext != null) {
          buildContext.refresh(gitPropsFile);
        }

      } else {
        log.info("Properties file [{}] is up-to-date (for module {})...", gitPropsFile.getAbsolutePath(), projectName);
      }
    } catch (IOException e) {
      throw new GitCommitIdExecutionException(e);
    }
  }

  public static File craftPropertiesOutputFile(File base, String propertiesFilename) {
    File returnPath = new File(base, propertiesFilename);

    File currentPropertiesFilepath = new File(propertiesFilename);
    if (currentPropertiesFilepath.isAbsolute()) {
      returnPath = currentPropertiesFilepath;
    }

    return returnPath;
  }

  private Properties readJsonProperties(@Nonnull File jsonFile, Charset sourceCharset) throws CannotReadFileException {
    final HashMap<String, Object> propertiesMap;

    try (final FileInputStream fis = new FileInputStream(jsonFile)) {
      try (final InputStreamReader reader = new InputStreamReader(fis, sourceCharset)) {
        final ObjectMapper mapper = new ObjectMapper();
        final TypeReference<HashMap<String, Object>> mapTypeRef =
                new TypeReference<HashMap<String, Object>>() {};

        propertiesMap = mapper.readValue(reader, mapTypeRef);
      }
    } catch (final Exception ex) {
      throw new CannotReadFileException(ex);
    }

    final Properties retVal = new Properties();

    for (final Map.Entry<String, Object> entry : propertiesMap.entrySet()) {
      retVal.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
    }

    return retVal;
  }

  private Properties readProperties(@Nonnull File propertiesFile, Charset sourceCharset) throws CannotReadFileException {
    try (final FileInputStream fis = new FileInputStream(propertiesFile)) {
      try (final InputStreamReader reader = new InputStreamReader(fis, sourceCharset)) {
        final Properties retVal = new Properties();
        retVal.load(reader);
        return retVal;
      }
    } catch (final Exception ex) {
      throw new CannotReadFileException(ex);
    }
  }

  static class CannotReadFileException extends Exception {
    private static final long serialVersionUID = -6290782570018307756L;

    CannotReadFileException(Throwable cause) {
      super(cause);
    }
  }
}
