# Değişiklik Günlüğü (Change Log)

## v3 — Bileşim: Filtreleme, Projeksiyon ve Pipeline Builder

Hat artık okur, ayrıştırır, filtreler, projeksiyon uygular ve dosyaya yazar; komut satırından yapılandırılır.

### Eklenen dosyalar
- `src/main/java/edu/logflow/PredicateFilterStage.java` — `Predicate<LogRecord>` ile eleme (`minStatus`, `pathPrefix`, `timeBetween`)
- `src/main/java/edu/logflow/ProjectionStage.java`, `ProjectedRecord.java` — alt küme alanlara indirgeme (veri minimizasyonu)
- `src/main/java/edu/logflow/FileSink.java` — dosyaya yazan sink (open/close)
- `src/main/java/edu/logflow/PipelineBuilder.java` — akıcı, tip güvenli kurucu (`from/via/to`)
- `src/main/java/edu/logflow/CliOptions.java`, `App.java` — komut satırı argümanları ve hat kurulumu
- `src/main/java/edu/logflow/OrderExperiment.java` — aşama sırası deneyi
- `ARCHITECTURE.md` — mimari kararlar ve deney sonuçları
- Testler: `PredicateFilterStageTest`, `ProjectionStageTest`, `FileSinkTest`, `PipelineBuilderTest`, `CliOptionsTest`, `IntegrationTest`

### Değiştirilen mevcut dosyalar
- `Sink.java` — `open()` / `close()` yaşam döngüsü kancaları (varsayılan gövdeleri boş; `ConsoleSink` etkilenmedi)
- `Pipeline.java` — kurucu mantık `PipelineBuilder`'a taşındı; `Pipeline` artık yalnızca çalıştırır
- `LineSource.java` → `FileLineSource.java` — yeniden adlandırıldı (ödev adı), davranış aynı
- `Main.java` — `App.run`'a devrediyor; hatalı satır sayısı artık `stderr`'e yazılıyor
- `PipelineTest.java` — `PipelineBuilder` / `FileLineSource` kullanacak şekilde güncellendi
- `README.md`, `CHANGELOG.md`, `pom.xml` (sürüm 3.0.0)

### Değişmeyen dosyalar
`Emitter`, `Stage`, `Source`, `LogRecord`, `ParserStage`, `ConsoleSink`, `data/access-small.log`
(ve v2 testleri: `ParserStageTest`, `LogRecordTest`, `ConsoleSinkTest`, `CollectingEmitter`)

### Not
Hatalı satırlar hâlâ yalnızca atlanır ve sayılır; doğru ele alınmaları 5. haftada gelecek.

## v2 — Tipli Kayıtlar ve İlk Gerçek Filtre

Hat artık dizgi değil, `LogRecord` taşıyor.

### Eklenen dosyalar
- `src/main/java/edu/logflow/LogRecord.java` — değişmez alan kaydı (`attributes` genişleme noktası)
- `src/main/java/edu/logflow/ParserStage.java` — CLF ayrıştırıcı; hatalı satırları atlar ve sayar
- `src/test/java/edu/logflow/CollectingEmitter.java` — toplayıcı emitter (test dublörü)
- `src/test/java/edu/logflow/ParserStageTest.java` — 11 test
- `src/test/java/edu/logflow/LogRecordTest.java`
- `src/test/java/edu/logflow/ConsoleSinkTest.java`
- `src/test/java/edu/logflow/PipelineTest.java`
- `README.md`, `CHANGELOG.md`

### Değiştirilen mevcut dosyalar
- `src/main/java/edu/logflow/Main.java` — hatta `.then(parser)` eklendi; sonda hatalı satır sayısı yazdırılıyor
- `src/main/java/edu/logflow/ConsoleSink.java` — `Sink<String>` → `Sink<LogRecord>`, tek satırlık biçim

### Değişmeyen dosyalar (komşu aşamalar)
`Pipeline`, `Stage`, `Emitter`, `Sink`, `Source`, `LineSource`, `pom.xml`, `data/access-small.log`

### Not
Hatalı satırlar bu hafta yalnızca atlanır ve sayılır; doğru ele alınmaları 5. haftada gelecek.

## v1 — İşlem hattı iskeleti
`Source`, `Stage`, `Emitter`, `Sink`, `Pipeline`, `LineSource`, `ConsoleSink` (dizgi), `Main`.
