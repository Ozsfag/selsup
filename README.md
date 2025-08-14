# CRPT API Test Task

Однофайловая реализация клиента для Честного знака на Java.

## 📌 Задача
Реализовать потокобезопасный класс для создания документа «Ввод в оборот (РФ)» с блокирующим rate limit.

- **Java**: 11+ (код совместим и с Java 21)
- **HTTP**: стандартный `java.net.http.HttpClient`
- **JSON**: Jackson
- **Rate limit**: скользящее окно, блокирующее выполнение

## 📂 Структура
- `CrptApi.java` — основной класс (и все вспомогательные внутренние классы).
- `Main.java` — пример использования.

## 📄 Пример вызова
    CrptApi api = new CrptApi(TimeUnit.SECONDS, 5);
    Map<String, Object> doc = Map.of("doc_id", "12345");
    CrptApi.DocumentId id = api.createIntroduceRf(doc, "signature", "token");
    System.out.println(id.value);

## 🚀 Запуск
```bash
./gradlew run 

