package dev.terraforge.geo.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ParallelScanTest {

    @Test
    void resultsComeBackInInputOrderWhateverThreadReadThem() {
        List<Integer> inputs = IntStream.range(0, 5_000).boxed().toList();
        Set<String> threads = ConcurrentHashMap.newKeySet();

        List<String> results = ParallelScan.map(inputs, value -> {
            threads.add(Thread.currentThread().getName());
            return "v" + value;
        });

        assertThat(results).containsExactlyElementsOf(inputs.stream().map(value -> "v" + value).toList());
        assertThat(threads).as("a directory this size is read on more than one thread").hasSizeGreaterThan(1);
        assertThat(threads).allSatisfy(name -> assertThat(name).startsWith("TerraForge-Scan-"));
    }

    @Test
    void aSmallDirectoryIsReadOnTheCallingThread() {
        String caller = Thread.currentThread().getName();

        List<String> results = ParallelScan.map(List.of(1, 2, 3), value -> Thread.currentThread().getName());

        assertThat(results).containsOnly(caller);
    }

    @Test
    void nullResultsKeepTheirPlace() {
        List<Integer> inputs = IntStream.range(0, 2_000).boxed().toList();

        List<Integer> results = ParallelScan.map(inputs, value -> value % 7 == 0 ? null : value);

        for (int index = 0; index < inputs.size(); index++) {
            assertThat(results.get(index)).isEqualTo(index % 7 == 0 ? null : index);
        }
    }

    @Test
    void anUncheckedFailureFailsTheScanAndLeavesNoThreadsBehind() {
        List<Integer> inputs = IntStream.range(0, 2_000).boxed().toList();

        assertThatThrownBy(() -> ParallelScan.map(inputs, value -> {
            if (value == 1_500) {
                throw new IllegalArgumentException("bad header " + value);
            }
            return value;
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("bad header 1500");
    }
}
