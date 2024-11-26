
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.jdbc.JdbcStatementBuilder;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.functions.windowing.AllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.util.Collector;
import org.apache.kafka.common.TopicPartition;
import org.apache.flink.api.common.functions.ReduceFunction;


import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.operators.Order;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.apache.flink.core.fs.FileSystem.WriteMode.OVERWRITE;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.PriorityQueue;

public class GithubTrendJob {
    static final String BROKERS = "kafka:9092";

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);

        System.out.println("Environment created");

        KafkaSource<GithubEventData> githubEventSource = KafkaSource.<GithubEventData>builder()
                                      .setBootstrapServers(BROKERS)
                                      .setProperty("partition.discovery.interval.ms", "1000")
                                      .setTopics("github-events")
                                      .setGroupId("groupdId-919292")
                                      .setStartingOffsets(OffsetsInitializer.earliest())
                                      .setValueOnlyDeserializer(new GithubEventDeserializationSchema())
                                      .build();
                        
        DataStreamSource<GithubEventData> githubEventStream = env.fromSource(githubEventSource, WatermarkStrategy.noWatermarks(), "kafka");

        System.out.println("Kafka source created");
        githubEventStream.print();
        
        SlidingEventTimeWindows windowSpec = SlidingEventTimeWindows.of(Time.minutes(10), Time.minutes(2));

        DataStream<Tuple2<String, Integer>> repoAndValueStream = githubEventStream
                .map(event -> new Tuple2<>(event.getRepo().getName(), 1))
                .returns(Types.TUPLE(Types.STRING, Types.INT))
                .keyBy(value -> value.f0)
                .window(SlidingProcessingTimeWindows.of(Time.minutes(10), Time.minutes(2)))
                .reduce(new ReduceFunction<Tuple2<String, Integer>>() {
                    public Tuple2<String, Integer> reduce(Tuple2<String, Integer> v1, Tuple2<String, Integer> v2) {
                      return new Tuple2<>(v1.f0, v1.f1 + v2.f1);
                    }
                  });
                
        repoAndValueStream.print();
        System.out.println("Aggregation created");

        DataStream<List<Tuple2<String, Integer>>> topTrendingRepos = repoAndValueStream
                .windowAll(TumblingProcessingTimeWindows.of(Time.minutes(2)))
                .process(new MyProcessWindowFunction());
        
        // topTrendingRepos.print();
        
        // Save top trending repositories to PostgreSQL
        topTrendingRepos
                .flatMap((List<Tuple2<String, Integer>> repos, Collector<Tuple2<String, Integer>> out) -> {
                        for (Tuple2<String, Integer> repo : repos) {
                            out.collect(repo);
                        }
                    })
                .returns(Types.TUPLE(Types.STRING, Types.INT))
                .addSink(
                    JdbcSink.sink(
                        "INSERT INTO trending_repositories (repo_name, action_count) VALUES (?, ?)",
                        (statement, repo) -> {
                            statement.setString(1, repo.f0);
                            statement.setInt(2, repo.f1);
                        },
                        JdbcExecutionOptions.builder()
                            .withBatchSize(1000)
                            .withBatchIntervalMs(200)
                            .withMaxRetries(5)
                            .build(),
                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                .withUrl("jdbc:postgresql://host.docker.internal:5438/postgres")
                                .withDriverName("org.postgresql.Driver")
                                .withUsername("postgres")
                                .withPassword("postgres")
                                .build()
                ));

        env.execute("Github-Trend-Job");
    }

    public static class MyProcessWindowFunction extends ProcessAllWindowFunction<Tuple2<String, Integer>, List<Tuple2<String, Integer>>, TimeWindow> {

        @Override
        public void process(Context context, Iterable<Tuple2<String, Integer>> input, Collector<List<Tuple2<String, Integer>>> out) throws Exception {
            List<Tuple2<String, Integer>> repoList = new ArrayList<>();
            input.forEach(repoList::add);

            repoList.sort(Comparator.comparingInt((Tuple2<String, Integer> tuple) -> tuple.f1).reversed());
            List<Tuple2<String, Integer>> top10 = new ArrayList<>(repoList.subList(0, Math.min(10, repoList.size())));
            
            // System.out.println("-------------"+ top10.toString());
            out.collect(top10);
        }
    }
}
