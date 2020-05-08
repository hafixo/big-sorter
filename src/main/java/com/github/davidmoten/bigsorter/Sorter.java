package com.github.davidmoten.bigsorter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.davidmoten.guavamini.Lists;
import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.guavamini.annotations.VisibleForTesting;

// NotThreadSafe
// The class is not considered thread safe because calling the sort() method on the same Sorter object simultaneously from 
// different threads could break things. Admittedly that would be a pretty strange thing to do! In short, create a new Sorter 
// and sort() in one thread, don't seek to reuse the same Sorter object. 
public final class Sorter<T> {

    private final List<Supplier<? extends InputStream>> inputs;
    private final Serializer<T> serializer;
    private final File output;
    private final Comparator<? super T> comparator;
    private final int maxFilesPerMerge;
    private final int maxItemsPerPart;
    private final Consumer<? super String> log;
    private final int bufferSize;
    private final File tempDirectory;
    private final Function<? super Reader<T>, ? extends Reader<? extends T>> transform;
    private final boolean unique;
    private long count = 0;
    

    Sorter(List<Supplier<? extends InputStream>> inputs, Serializer<T> serializer, File output,
            Comparator<? super T> comparator, int maxFilesPerMerge, int maxItemsPerFile,
            Consumer<? super String> log, int bufferSize, File tempDirectory,
            Function<? super Reader<T>, ? extends Reader<? extends T>> transform, boolean unique) {
        Preconditions.checkNotNull(inputs, "inputs cannot be null");
        Preconditions.checkNotNull(serializer, "serializer cannot be null");
        Preconditions.checkNotNull(output, "output cannot be null");
        Preconditions.checkNotNull(comparator, "comparator cannot be null");
        Preconditions.checkNotNull(transform, "transform cannot be null");
        this.inputs = inputs;
        this.serializer = serializer;
        this.output = output;
        this.comparator = comparator;
        this.maxFilesPerMerge = maxFilesPerMerge;
        this.maxItemsPerPart = maxItemsPerFile;
        this.log = log;
        this.bufferSize = bufferSize;
        this.tempDirectory = tempDirectory;
        this.transform = transform;
        this.unique = unique;
    }

    public static <T> Builder<T> serializer(Serializer<T> serializer) {
        Preconditions.checkNotNull(serializer, "serializer cannot be null");
        return new Builder<T>(serializer);
    }

    public static <T> Builder<String> serializerLinesUtf8() {
        return serializer(Serializer.linesUtf8());
    }

    public static <T> Builder<String> serializerLines(Charset charset) {
        return serializer(Serializer.lines(charset));
    }

    public static <T> Builder2<String> lines(Charset charset) {
        return serializer(Serializer.lines(charset)).comparator(Comparator.naturalOrder());
    }

    public static <T> Builder2<String> linesUtf8() {
        return serializer(Serializer.linesUtf8()).comparator(Comparator.naturalOrder());
    }

    public static final class Builder<T> {
        private static final DateTimeFormatter DATE_TIME_PATTERN = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.Sxxxx");
        private List<Supplier<? extends InputStream>> inputs = Lists.newArrayList();
        private final Serializer<T> serializer;
        private File output;
        private Comparator<? super T> comparator;
        private int maxFilesPerMerge = 100;
        private int maxItemsPerFile = 100000;
        private Consumer<? super String> logger = null;
        private int bufferSize = 8192;
        private File tempDirectory = new File(System.getProperty("java.io.tmpdir"));
        private Function<? super Reader<T>, ? extends Reader<? extends T>> transform = r -> r;
        public boolean unique;

        Builder(Serializer<T> serializer) {
            this.serializer = serializer;
        }

        public Builder2<T> comparator(Comparator<? super T> comparator) {
            Preconditions.checkNotNull(comparator, "comparator cannot be null");
            this.comparator = comparator;
            return new Builder2<T>(this);
        }
    }

    public static final class Builder2<T> {
        private final Builder<T> b;

        Builder2(Builder<T> b) {
            this.b = b;
        }

        public Builder3<T> input(Charset charset, String... strings) {
            Preconditions.checkNotNull(strings, "string cannot be null");
            Preconditions.checkNotNull(charset, "charset cannot be null");
            List<Supplier<InputStream>> list = Arrays //
                    .asList(strings) //
                    .stream() //
                    .map(string -> new ByteArrayInputStream(string.getBytes(charset))) //
                    .map(bis -> (Supplier<InputStream>)(() -> bis)) //
                    .collect(Collectors.toList());
            return inputStreams(list);
        }

        public Builder3<T> input(String... strings) {
            Preconditions.checkNotNull(strings);
            return input(StandardCharsets.UTF_8, strings);
        }
        
        public Builder3<T> input(InputStream... inputs) {
            List<Supplier<InputStream>> list = Lists.newArrayList();
            for (InputStream in:inputs) {
                list.add(() -> new NonClosingInputStream(in));
            }
            return inputStreams(list);
        }
        
        public Builder3<T> input(Supplier<? extends InputStream> input) {
            Preconditions.checkNotNull(input, "input cannot be null");
            return inputStreams(Collections.singletonList(input));
        }
        
        public Builder3<T> input(File... files) {
            return input(Arrays.asList(files));
        }

        public Builder3<T> input(List<File> files) {
            Preconditions.checkNotNull(files, "files cannot be null");
            return inputStreams(files //
                    .stream() //
                    .map(file -> supplier(file)) //
                    .collect(Collectors.toList()));
        }
        
        public Builder3<T> inputStreams(List<? extends Supplier<? extends InputStream>> inputs) {
            Preconditions.checkNotNull(inputs);
            for (Supplier<? extends InputStream> input: inputs) {
                b.inputs.add(input);
            }
            return new Builder3<T>(b);
        }
        
        private Supplier<InputStream> supplier(File file) {
            return () -> {
                try {
                    return openFile(file, b.bufferSize);
                } catch (FileNotFoundException e) {
                    throw new UncheckedIOException(e);
                }
            };
        }
        
    }

    public static final class Builder3<T> {
        private final Builder<T> b;

        Builder3(Builder<T> b) {
            this.b = b;
        }
        
        public Builder3<T> filter(Predicate<? super T> predicate) {
            Function<? super Reader<T>, ? extends Reader<? extends T>> currentTransform = b.transform;
            return transform(r -> currentTransform.apply(r).filter(predicate));
        }
        
        @SuppressWarnings("unchecked")
        public Builder3<T> map(Function<? super T, ? extends T> mapper) {
            Function<? super Reader<T>, ? extends Reader<? extends T>> currentTransform = b.transform;
            return transform(r -> ((Reader<T>) currentTransform.apply(r)).map(mapper));
        }
        
        @SuppressWarnings("unchecked")
        public Builder3<T> flatMap(Function<? super T, ? extends List<? extends T>> mapper) {
            Function<? super Reader<T>, ? extends Reader<? extends T>> currentTransform = b.transform;
            return transform(r -> ((Reader<T>) currentTransform.apply(r)).flatMap(mapper));
        }

        @SuppressWarnings("unchecked")
        public Builder3<T> transform(
                Function<? super Reader<T>, ? extends Reader<? extends T>> transform) {
            Preconditions.checkNotNull(transform, "transform cannot be null");
            Function<? super Reader<T>, ? extends Reader<? extends T>> currentTransform = b.transform;
            b.transform = r -> transform.apply((Reader<T>) currentTransform.apply(r));
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder3<T> transformStream(
                Function<? super Stream<T>, ? extends Stream<? extends T>> transform) {
            Preconditions.checkNotNull(transform, "transform cannot be null");
            Function<? super Reader<T>, ? extends Reader<? extends T>> currentTransform = b.transform;
            b.transform = r -> ((Reader<T>) currentTransform.apply(r)).transform(transform);
            return this;
        }

        public Builder4<T> output(File output) {
            Preconditions.checkNotNull(output, "output cannot be null");
            b.output = output;
            return new Builder4<T>(b);
        }

    }

    public static final class Builder4<T> {
        private final Builder<T> b;

        Builder4(Builder<T> b) {
            this.b = b;
        }

        public Builder4<T> maxFilesPerMerge(int value) {
            Preconditions.checkArgument(value > 1, "maxFilesPerMerge must be greater than 1");
            b.maxFilesPerMerge = value;
            return this;
        }

        public Builder4<T> maxItemsPerFile(int value) {
            Preconditions.checkArgument(value > 0, "maxItemsPerFile must be greater than 0");
            b.maxItemsPerFile = value;
            return this;
        }
        
        public Builder4<T> unique(boolean value) {
            b.unique = value;
            return this;
        }
        
        public Builder4<T> unique() {
            return unique(true);
        }

        public Builder4<T> logger(Consumer<? super String> logger) {
            Preconditions.checkNotNull(logger, "logger cannot be null");
            b.logger = logger;
            return this;
        }

        // public Builder4<T> async() {
        // b.executor = Executors.newSingleThreadExecutor();
        // return this;
        // }

        public Builder4<T> loggerStdOut() {
            return logger(new Consumer<String>() {

                @Override
                public void accept(String msg) {
                    System.out.println(ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS)
                            .format(Builder.DATE_TIME_PATTERN) + " " + msg);
                }
            });
        }

        public Builder4<T> bufferSize(int bufferSize) {
            Preconditions.checkArgument(bufferSize > 0, "bufferSize must be greater than 0");
            b.bufferSize = bufferSize;
            return this;
        }

        public Builder4<T> tempDirectory(File directory) {
            Preconditions.checkNotNull(directory, "tempDirectory cannot be null");
            b.tempDirectory = directory;
            return this;
        }
        
        /**
         * Sorts the input and writes the result to the given output file. If an
         * {@link IOException} occurs then it is thrown wrapped in
         * {@link UncheckedIOException}.
         */
        public void sort() {
            Sorter<T> sorter = new Sorter<T>(b.inputs, b.serializer, b.output, b.comparator,
                    b.maxFilesPerMerge, b.maxItemsPerFile, b.logger, b.bufferSize, b.tempDirectory,
                    b.transform, b.unique);
            try {
                sorter.sort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

    }

    static InputStream openFile(File file, int bufferSize) throws FileNotFoundException {
        return new BufferedInputStream(new FileInputStream(file), bufferSize);
    }

    private void log(String msg, Object... objects) {
        if (log != null) {
            String s = String.format(msg, objects);
            log.accept(s);
        }
    }

    private File sort() throws IOException {

        tempDirectory.mkdirs();

        // read the input into sorted small files
        long time = System.currentTimeMillis();
        count = 0;
        List<File> files = new ArrayList<>();
        log("starting sort");
        log("unique = " + unique);
        
        int i = 0;
        ArrayList<T> list = new ArrayList<>();
        for (Supplier<? extends InputStream> supplier: inputs) {
            try (InputStream in = supplier.get();
                    Reader<? extends T> reader = transform.apply(serializer.createReader(in))) {
                while (true) {
                    T t = reader.read();
                    if (t != null) {
                        list.add(t);
                        i++;
                    }
                    if (t == null || i == maxItemsPerPart) {
                        i = 0;
                        if (list.size() > 0) {
                            File f = sortAndWriteToFile(list);
                            files.add(f);
                            list.clear();
                        }
                    }
                    if (t == null) {
                        break;
                    }
                }
            }
        }
        log("completed inital split and sort, starting merge");
        File result = merge(files);
        Files.move( //
                result.toPath(), //
                output.toPath(), //
                StandardCopyOption.ATOMIC_MOVE, //
                StandardCopyOption.REPLACE_EXISTING);
        log("sort of " + count + " records completed in "
                + (System.currentTimeMillis() - time) / 1000.0 + "s");
        return output;
    }

    @VisibleForTesting
    File merge(List<File> files) {
        // merge the files in chunks repeatededly until only one remains
        // TODO make a better guess at the chunk size so groups are more even
        try {
            while (files.size() > 1) {
                List<File> nextRound = new ArrayList<>();
                for (int i = 0; i < files.size(); i += maxFilesPerMerge) {
                    File merged = mergeGroup(
                            files.subList(i, Math.min(files.size(), i + maxFilesPerMerge)));
                    nextRound.add(merged);
                }
                files = nextRound;
            }
            File result;
            if (files.isEmpty()) {
                output.delete();
                output.createNewFile();
                result = output;
            } else {
                result = files.get(0);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private File mergeGroup(List<File> list) throws IOException {
        log("merging %s files", list.size());
        if (list.size() == 1) {
            return list.get(0);
        }
        List<State<T>> states = new ArrayList<>();
        for (File f : list) {
            State<T> st = createState(f);
            // note that st.value will be present otherwise the file would be empty
            // and an empty file would not be passed to this method
            states.add(st);
        }
        File output = nextTempFile();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output), bufferSize);
                Writer<T> writer = serializer.createWriter(out)) {
            PriorityQueue<State<T>> q = new PriorityQueue<>(
                    (x, y) -> comparator.compare(x.value, y.value));
            q.addAll(states);
            T last = null;
            while (!q.isEmpty()) {
                State<T> state = q.poll();
                if (!unique || last == null || comparator.compare(state.value, last) != 0) {
                    writer.write(state.value);
                    last = state.value;
                }
                state.value = state.reader.readAutoClosing();
                if (state.value != null) {
                    q.offer(state);
                } else {
                    // delete intermediate files
                    state.file.delete();
                }
            }
            // TODO if an IOException occurs then we should attempt to close and delete
            // temporary files
        }
        return output;
    }

    private State<T> createState(File f) throws IOException {
        InputStream in = openFile(f, bufferSize);
        Reader<T> reader = serializer.createReader(in);
        T t = reader.readAutoClosing();
        return new State<T>(f, reader, t);
    }

    private static final class State<T> {
        final File file;
        Reader<T> reader;
        T value;

        State(File file, Reader<T> reader, T value) {
            this.file = file;
            this.reader = reader;
            this.value = value;
        }
    }

    private File sortAndWriteToFile(ArrayList<T> list) throws FileNotFoundException, IOException {
        File file = nextTempFile();
        long t = System.currentTimeMillis();
        list.sort(comparator);
        writeToFile(list, file);
        DecimalFormat df = new DecimalFormat("0.000");
        count += list.size();
        log("total=%s, sorted %s records to file %s in %ss", //
                count, //
                list.size(), //
                file.getName(), //
                df.format((System.currentTimeMillis() - t) / 1000.0));
        return file;
    }

    private void writeToFile(List<T> list, File f) throws FileNotFoundException, IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f), bufferSize);
                Writer<T> writer = serializer.createWriter(out)) {
            T last = null;
            for (T t : list) {
                if (!unique || last == null || comparator.compare(t, last) != 0) {
                    writer.write(t);
                    last = t;
                }
            }
        }
    }

    private File nextTempFile() throws IOException {
        return File.createTempFile("big-sorter", "", tempDirectory);
    }

}
