# Değişiklik Günlüğü (Change Log)

## v4 — Yapılandırma Odaklı Birleştirme ve Aşama Kaydı

Hattın topolojisi YAML dosyasına taşındı: yeni hat için yeniden derleme gerekmez.

### Eklenen dosyalar
- `config/PipelineLoader.java`, `PipelineConfig.java`, `LoadedPipeline.java` — yükleyici ve yapısal model
- `config/StageFactory.java`, `SourceFactory.java`, `SinkFactory.java` — tür fabrikası arayüzleri
- `config/Registry.java`, `StageRegistry.java`, `SourceRegistry.java`, `SinkRegistry.java` — `ServiceLoader` tabanlı kayıt (switch yok)
- `config/Params.java`, `ConfigException.java`, `MiniYaml.java` — parametre doğrulama, yapılandırma hatası, YAML alt kümesi ayrıştırıcısı
- `plugins/` — `FileSourceFactory`, `ParseStageFactory`, `FilterStageFactory`, `ProjectStageFactory`, `FileSinkFactory`, `ConsoleSinkFactory`
- `Summarizing.java` — çalışma sonu özeti sunan aşama arayüzü
- `src/main/resources/META-INF/services/` — tür kayıtları (3 dosya)
- `configs/error-report.yaml`, `api-traffic.yaml`, `bandwidth-by-client.yaml` ve `configs/invalid/` (7 hatalı örnek)
- Testler: `config/PipelineLoaderTest`, `config/RegistryTest`, `config/MiniYamlTest`, `ConfigIntegrationTest`
- `ARCHITECTURE.md`: yapılandırma şeması, kayıt sistemi, yapılandırma/veri hatası ayrımı (Artım 4 bölümü)

### Değiştirilen mevcut dosyalar
- `App.java` — `--config` kipi eklendi (yapılandırma hataları çıkış kodu 2)
- `CliOptions.java` — `--config` ve `--dry-run` seçenekleri
- `FileSink.java` — eksik üst dizinleri `open()`'da oluşturur (`out/errors.txt`)
- `ParserStage.java` — `Summarizing` uygular (özet satırı yükleyici tarafından toplanabilir)
- `CliOptionsTest.java`, `FileSinkTest.java` — yeni davranışlar için genişletildi
- `README.md`, `CHANGELOG.md`, `pom.xml` (sürüm 4.0.0), `.gitignore` (`out/`)

### Değişmeyen dosyalar
`PipelineBuilder`, `Pipeline`, `Sink`, `Stage`, `Emitter`, `Source`, `FileLineSource`, `PredicateFilterStage`,
`ProjectionStage`, `ProjectedRecord`, `LogRecord`, `ConsoleSink`, `Main`, `OrderExperiment`

### Not
Yapılandırma zamanı hataları hemen ve açıkça bildirilir; çalışma zamanı veri hataları (bozuk satırlar)
hâlâ yalnızca atlanır ve sayılır, doğru ele alınmaları 5. haftada gelecek.

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
