/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import static com.facebook.buck.cxx.DebugSectionProperty.COMPRESSED;
import static com.facebook.buck.cxx.DebugSectionProperty.STRINGS;
import static java.nio.channels.FileChannel.MapMode.READ_WRITE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Encapsulates all the logic to sanitize debug paths in native code.  Currently, this just
 * supports sanitizing the compilation directory that compilers typically embed in binaries
 * when including debug sections (e.g. using "-g" with gcc/clang inserts the compilation
 * directory in the DW_AT_comp_dir DWARF field).
 */
public class DebugPathSanitizer {

  private static final DebugSectionFinder DEBUG_SECTION_FINDER = new DebugSectionFinder();

  private final int pathSize;
  private final char separator;
  private final Path compilationDirectory;

  /**
   * @param pathSize fix paths to this size for in-place replacements.
   * @param separator the path separator used to fill paths aren't of {@code pathSize} length.
   * @param compilationDirectory the desired path to replace the actual compilation directory with.
   */
  public DebugPathSanitizer(
      int pathSize,
      char separator,
      Path compilationDirectory) {
    this.pathSize = pathSize;
    this.separator = separator;
    this.compilationDirectory = compilationDirectory;
  }

  /**
   * Constructs a {@link DebugPathSanitizer} using a string name for the compilation directory,
   * which is padded with 'X' characters to {@code pathSize}.
   */
  public DebugPathSanitizer(
      int pathSize,
      char separator,
      String compilationDirectory) {
    this(pathSize, separator, Paths.get(getReplacementString(compilationDirectory, pathSize)));
  }

  /**
   * @return a string formed by padding {@code name} on either side with 'X' characters to length
   *     {@code size}, suitable to replace paths embedded in the debug section of a binary.
   */
  private static String getReplacementString(String name, int size) {
    Preconditions.checkArgument(size >= name.length());
    return Strings.padEnd(name, size, 'X');
  }

  /**
   * @return the given path as a string, expanded using {@code separator} to fulfill the required
   *     {@code pathSize}.
   */
  public String getExpandedPath(Path path) {
    Preconditions.checkArgument(path.toString().length() <= pathSize);
    return Strings.padEnd(path.toString(), pathSize, separator);
  }

  public String getCompilationDirectory() {
    return getExpandedPath(compilationDirectory);
  }

  /**
   * Run {@code replacer} on all relevant debug sections in {@code buffer}, falling back to
   * processing the entire {@code buffer} if the format is unrecognized.
   */
  private void restore(ByteBuffer buffer, ByteBufferReplacer replacer) {

    // Find the debug sections in the file represented by the buffer.
    Optional<ImmutableMap<String, DebugSection>> results = DEBUG_SECTION_FINDER.find(buffer);

    // If we were able to recognize the file format and find debug symbols, perform the
    // replacement on them.  Otherwise, just do a find-and-replace on the whole blob.
    if (results.isPresent()) {
      for (DebugSection section : results.get().values()) {
        // We can't do in-place updates on compressed debug sections.
        Preconditions.checkState(!section.properties.contains(COMPRESSED));
        if (section.properties.contains(STRINGS)) {
          replacer.replace(section.body);
        }
      }
    } else {
      replacer.replace(buffer);
    }
  }

  private void restore(Path path, ByteBufferReplacer replacer) throws IOException {
    try (FileChannel channel = FileChannel.open(path, READ, WRITE)) {
      MappedByteBuffer buffer = channel.map(READ_WRITE, 0, channel.size());
      restore(buffer, replacer);
    }
  }

  /**
   * @return a {@link ByteBufferReplacer} suitable for replacing {@code workingDir} with
   *     {@code compilationDirectory}.
   */
  private ByteBufferReplacer getCompilationDirectoryReplacer(Path workingDir) {
    return new ByteBufferReplacer(
        ImmutableMap.of(
            getExpandedPath(workingDir).getBytes(Charsets.US_ASCII),
            getExpandedPath(compilationDirectory).getBytes(Charsets.US_ASCII)));
  }

  // Construct the replacer, giving the expanded current directory and the desired directory.
  // We use ASCII, since all the relevant debug standards we care about (e.g. DWARF) use it.
  public void restoreCompilationDirectory(Path path, Path workingDir) throws IOException {
    restore(path, getCompilationDirectoryReplacer(workingDir));
  }

}
