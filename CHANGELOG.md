# Değişiklik Günlüğü (Change Log)

## v5 — Hata Yönetimi, Doğrulama ve Dead-Letter

Bozuk veri artık sessizce yutulmuyor: nedeniyle sınıflanıyor, politikaya göre atlanıyor / dead-letter'a yazılıyor /
çalışmayı durduruyor, ve çalışma sonunda **hiçbir kaydın kaybolmadığı** denetleniyor.

### Eklenen dosyalar
- `error/` — `ErrorType`, `StageException`, `PipelineException`, `AbortRun`, `ErrorPolicy`, `FailedRecord`, `ErrorRouter`,
  `GuardedStage`, `GuardedSink`, `TrackingSource`, `RawLineContext`, `JsonlSink`, `RunSummary`, `ManagedRun`
- `RawLine.java`, `RawLineSource.java` — bayt düzeyinde satır okuyucu (boyut sınırı, kodlama çözmez)
- `DecodeStage.java` — katı UTF-8 çözücü; `ValidationStage.java` — bildirimli doğrulama kuralları
- `plugins/RawFileSourceFactory`, `DecodeStageFactory`, `ValidateStageFactory` (+ `META-INF/services` satırları)
- `configs/dirty-dead-letter.yaml`, `dirty-skip.yaml`, `dirty-fail-fast.yaml`; `configs/invalid/` altında 4 yeni hatalı örnek
- `data/access-dirty.log`, `data/access-empty.log`, `data/README.txt` (kasıtlı bozulmuş test verisi), `.gitattributes`
- Testler: `error/ErrorPolicyTest`, `ErrorRoutingTest`, `GuardedSinkTest`, `ManagedRunTest`, `JsonlSinkTest`,
  `RawLineSourceTest`, `DecodeStageTest`, `ValidationStageTest`, `ChaosTest`
- `ARCHITECTURE.md`: Artım 5 bölümü (hata modeli, politikalar, mutabakat, kararların gerekçesi)

### Değiştirilen mevcut dosyalar
- `ParserStage.java` — hatalı satırı artık sessizce atlamaz, nedenli `StageException` fırlatır; sayaç kaldırıldı
- `App.java` — hat `ManagedRun` ile çalışır; özet `stderr`'e; çıkış kodları 0/1/2/3 (eski komut satırı kipi de `skip` politikasıyla sarılı)
- `config/PipelineConfig.java` (`on-error`, `fallback`, `errors.dead-letter`), `PipelineLoader.java` (sarmalama,
  politika denetimi), `LoadedPipeline.java` (`run()` → `RunSummary`), `Params.java` (isteğe bağlı erişimciler),
  `StageFactory.java` (`filtering()`), `plugins/FilterStageFactory.java` (`filtering() = true`)
- Testler yeni davranışa uyarlandı: `ParserStageTest`, `PipelineTest`, `IntegrationTest`, `ConfigIntegrationTest`,
  `config/PipelineLoaderTest`, `config/RegistryTest`
- `README.md`, `CHANGELOG.md`, `pom.xml` (sürüm 5.0.0)

### Silinen dosyalar
- `Summarizing.java` — özeti artık aşama değil `RunSummary` verir (tek doğruluk kaynağı)

### Değişmeyen dosyalar
`PipelineBuilder`, `Pipeline`, `Stage`, `Source`, `Sink`, `Emitter`, `FileLineSource`, `FileSink`, `ConsoleSink`,
`PredicateFilterStage`, `ProjectionStage`, `ProjectedRecord`, `LogRecord`, `CliOptions`, `Main`, `config/MiniYaml`

### Davranış değişiklikleri
- Hatalı satır özeti: `Hatali (atlanan) satir sayisi: N` yerine `Calisma ozeti` bloğu (`atlanan (skip)  : N` ...).
- Yeni çıkış kodu 3 (kayıt kaybı). 1 hâlâ "çalışma tamamlanmadı", 2 hâlâ "argüman/yapılandırma hatası".

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
