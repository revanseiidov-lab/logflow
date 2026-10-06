# Değişiklik Günlüğü (Change Log)

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
