package dev.iconmanager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExportSizes {
    static final long WARNING_BYTES = 290L * 1024 * 1024;
    static final List<Integer> OPTIONS = List.of(256, 192, 128, 96);
    private final Map<String, Integer> sizes;
    final int maximum;

    static boolean valid(int size) { return OPTIONS.contains(size); }
    static boolean oversized(long bytes) { return bytes > WARNING_BYTES; }

    ExportSizes(IconRepository.Pack pack, Map<String, Integer> settings) throws IOException {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        int max = 0;
        for (IconRepository.Pack member : pack.members.isEmpty() ? List.of(pack) : pack.members) {
            Integer chosen = settings.get(member.packageName);
            if (chosen != null && !valid(chosen)) throw new IOException(App.text(R.string.export_size_invalid));
            int size = chosen == null ? member.iconSize() : chosen;
            resolved.put(member.packageName, size);
            max = Math.max(max, size);
        }
        sizes = Map.copyOf(resolved);
        maximum = max;
    }

    int forIcon(String reference, String primaryPackage) {
        if (FileIcons.isFile(reference) || reference.startsWith(ThemeEngine.SYSTEM_ICON)) return maximum;
        return sizes.getOrDefault(IconRepository.sourcePackage(reference, primaryPackage), maximum);
    }
}
