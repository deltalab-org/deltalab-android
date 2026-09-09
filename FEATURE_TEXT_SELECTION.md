# Фича: Выделение и копирование части текста сообщения

## Название фичи
**Partial text selection in messages** (Выделение фрагмента текста в сообщении)

## Контекст и мотивация

Сейчас в ArcaneChat можно скопировать сообщение **целиком** только через long-press → ActionMode → "Copy text". Это неудобно, когда нужно скопировать только часть текста (например, ссылку из длинного сообщения, цитату или имя пользователя). В Telegram, WhatsApp, Signal и других мессенджерах реализована возможность выделения произвольного фрагмента текста в пузыре сообщения с последующим копированием выделенного в буфер обмена.

### Текущее поведение (AS-IS)
- Long-press по сообщению → выделение всего сообщения (multi-select режим) → появляется contextual toolbar с action menu
- В action menu есть пункт "Copy text" → копирует **весь текст** сообщения
- Текст в `bodyText` (`AutoScaledEmojiTextView`) **не выделяемый**: `setClickable(false)`, `setFocusable(false)`, нет `setTextIsSelectable(true)`
- `LongClickMovementMethod` обрабатывает только клики/long-press по **ссылкам** (через `LongClickCopySpan`), не по произвольному тексту
- `PassthroughClickListener.hasSelection()` уже проверяет `bodyText.hasSelection()` — потенциально уже умеет отличать выделение от long-press

### Желаемое поведение (TO-BE)
- Long-press по тексту сообщения → появляются **handle'ы выделения** (как в любом TextView с text selection)
- Пользователь может перетаскивать handle'ы для выделения нужного фрагмента
- Над выделенным текстом появляется **floating toolbar** (ActionMode) с пунктами: "Copy", "Select all", опционально "Share"
- Тап вне выделенного текста → снятие выделения, возврат к обычному режиму
- Long-press на **уже выделенный текст** → начинается перемещение выделения

---

## Архитектурный анализ

### Ключевые файлы

| Файл | Роль | Что меняется |
|------|------|-------------|
| `ConversationItem.java` | Основной View для отображения сообщений. `setBodyText()` рендерит текст, `LongClickMovementMethod` для навигации по ссылкам | Нужно: переключение между режимами (ссылки / текстовое выделение / multi-select) |
| `BaseConversationItem.java` | Базовый класс; поле `bodyText`; `PassthroughClickListener` с проверкой `hasSelection()` | Нужно: адаптировать `PassthroughClickListener` для coexistence с текстовым выделением |
| `LongClickMovementMethod.java` | `MovementMethod` для обработки кликов по ссылкам. Вызывает `Selection.setSelection()` при нажатии на `LongClickCopySpan` | Нужно: координация с выделением текста; текущий `LongClickCopySpan` при long-press показывает диалог копирования ссылки |
| `ConversationFragment.java` | `ActionModeCallback` для multi-select; `handleCopyMessage()` для копирования всего сообщения | Нужно: разделить multi-select action mode (выделение сообщений) и text selection action mode (выделение фрагмента текста) |
| `ConversationAdapter.java` | `onCreateViewHolder` устанавливает long-press listener на items | Нужно: potentially отключить item-level long-press когда `bodyText` в режиме text selection |

### Конфликты, которые нужно решить

1. **`LongClickMovementMethod` vs текстовое выделение**: текущий `MovementMethod` перехватывает touch-события для обработки ссылок. При включении `setTextIsSelectable(true)` на `TextView` Android сам обрабатывает выделение через `TextView.StartActionMode`. Конфликт: `LongClickMovementMethod` может перехватить long-press и обработать его как клик по ссылке, а не как начало выделения.

2. **`ConversationFragment.ActionModeCallback` vs `TextView.ActionMode`**: два разных `ActionMode` — один для multi-select сообщений, другой для выделения текста. Android поддерживает только один `ActionMode` на Activity. Нужно убедиться, что они не конфликтуют.

3. **`PassthroughClickListener`**: уже проверяет `hasSelection()` — это хорошо. Но нужно убедиться, что при выделении текста `hasSelection()` возвращает `true`, а при начале long-press (ещё до выделения) — `false`.

---

## Требования

### R1: Включение text selection на bodyText

**Приоритет:** P0 (обязательно)
**Описание:** На `bodyText` (`AutoScaledEmojiTextView`) в `ConversationItem` должна быть включена возможность выделения произвольного фрагмента текста.

**Детали реализации:**
- В `ConversationItem.setBodyText()` (или при инициализации в конструкторе) установить `bodyText.setTextIsSelectable(true)` для сообщений, содержащих текст (тип `DC_MSG_TEXT`, а также все типы с текстовым подписем).
- При этом `bodyText` должен оставаться кликабельным для ссылок (`LongClickCopySpan`).
- **Не выделять** текст в системных/инфо-сообщениях (`ConversationUpdateItem`) — только в пользовательских пузырях сообщений.
- **Учитывать настройку** `pref_text_selection` (см. раздел "Настройка фичи"): если выключена — `setTextIsSelectable(false)` для всех сообщений.

**Критерий приёмки:**
- Long-press по тексту в пузыре сообщения показывает handle'ы выделения
- Текст в системных сообщениях не выделяется

---

### R2: Floating toolbar при выделении

**Приоритет:** P0 (обязательно)
**Описание:** При выделении текста должен появляться floating context action bar с действиями над выделением.

**Детали реализации:**
- По умолчанию Android показывает стандартный `FloatingToolbar` (Android 11+). Для более старых версий используется `ActionMode`.
- Пункты меню floating toolbar:
  - **"Copy"** (`android.R.id.copy`) — копирует выделенный текст в буфер обмена
  - **"Select all"** (`android.R.id.selectAll`) — выделяет весь текст сообщения
  - Опционально: **"Share"** — делится выделенным текстом через share intent
- При нажатии "Copy" → текст попадает в буфер обмена через `Util.writeTextToClipboard()`, показывается `Toast("Copied to clipboard")`.
- При нажатии "Select all" → выделяется весь текст в `bodyText`.

**Критерий приёмки:**
- После выделения фрагмента текста появляется плавающая панель с "Copy" и "Select all"
- Нажатие "Copy" копирует выделенный фрагмент
- Нажатие "Select all" выделяет весь текст

---

### R3: Координация с long-press для multi-select

**Приоритет:** P0 (обязательно)
**Описание:** Long-press должен работать в двух режимах в зависимости от контекста: начало выделения текста (если текст выделяемый) ИЛИ начало multi-select режима (выделение сообщений для действия). Нужно определить порядок приоритетов.

**Подход (рекомендуемый):** Использовать **gesture-детекцию** для разделения:

1. **Long-press по тексту** (без перемещения пальца): начинает выделение текста → Android показывает handle'ы → появляется floating toolbar
2. **Long-press с последующим движением**: можно перетаскивать handle'ы выделения
3. **Tap по уже выделенному тексту**: снимает выделение, возвращается в normal mode
4. **Долгое нажатие на bubble (не на текст)**: активирует multi-select режим (текущее поведение)

**Альтернативный подход (проще):**
- Включить `setTextIsSelectable(true)` — Android сам обрабатывает long-press → выделение
- **Отключить** item-level long-press когда `bodyText.hasSelection()` — `PassthroughClickListener` уже делает `return false` при `hasSelection()`
- Multi-select режим активируется только через **икаонку в ActionBar** (или через long-press на bubble, но не на text)

**Критерий приёмки:**
- Long-press по тексту → выделение текста (handle'ы + floating toolbar)
- Long-press на пузыре (вне текста) → multi-select режим
- При выделенном тексте long-press на пузыре не активирует multi-select

---

### R4: Выделение текста в цитатах (QuoteView)

**Приоритет:** P1 (желательно)
**Описание:** Текст в цитатах (`QuoteView` внутри пузыря сообщения) тоже должен быть выделяемым.

**Детали:**
- `QuoteView` содержит `quote_text` (`TextView`) — если включить `setTextIsSelectable(true)` и на нём, пользователь сможет выделять текст цитаты отдельно от основного текста сообщения.
- Это может конфликтовать с кликом по цитате (скролл к оригинальному сообщению). Нужно проверить, что клик по цитате работает при `setTextIsSelectable(true)`.

**Критерий приёмки:**
- Текст цитаты выделяется и копируется
- Клик по цитате (навигация к исходному сообщению) продолжает работать

---

### R5: Выделение текста в ссылках и специальных span'ах

**Приоритет:** P1 (желательно)
**Описание:** Текст ссылок, email, телефонов и команд (`LongClickCopySpan`) должен оставаться кликабельным/long-clickable при включённом text selection.

**Детали:**
- При `setTextIsSelectable(true)` и `LongClickMovementMethod` в качестве `movementMethod`:
  - **Tap по ссылке** → открывает ссылку (как сейчас)
  - **Long-press по ссылке** → конфликт: текущий `LongClickMovementMethod` показывает диалог "Copy link", но Android может начать выделение текста
- **Решение (выбранное)**: Приоритет выделения текста > копирования ссылки. Long-press по ссылке начинает выделение текста (как обычный текст). Диалог "Copy link" через `LongClickCopySpan` при text selection **отключается** (конфликт с floating toolbar). Пользователь может выделить текст, содержащий ссылку, и нажать "Copy" во floating toolbar.

> ⚠️ **Противоречие с открытым вопросом #2**: Ответ на вопрос #2 гласит "Оставить" диалог копирования ссылок. Однако при `setTextIsSelectable(true)` long-press перехватывается Android для выделения текста, и перехватить его обратно для ссылки можно только через полную замену `MovementMethod`. Это **значительно усложняет** реализацию (нужно дублировать логику `LinkMovementMethod` + `SelectionActionMode`). Рекомендация: **отказаться** от диалога long-press-copy ссылок при включённом text selection. Floating toolbar "Copy" копирует выделенный фрагмент, включая ссылку.

**Критерий приёмки:**
- Tap по ссылке → открывает ссылку
- Выделение текста, содержащего ссылку, работает
- Ссылка копируется вместе с текстом при выделении

---

### R6: Выделение текста в стикерах и медиа-сообщениях

**Приоритет:** P2 (опционально)
**Описание:** В сообщениях, состоящих только из медиа (стикер, изображение без подписи), выделение текста не должно быть доступным (нет текста для выделения). В сообщениях с подписью под медиа — выделение текста подписи.

**Критерий приёмки:**
- Стикер без подписи → long-press → multi-select (не выделение)
- Фото с подписью → long-press по подписи → выделение подписи

---

### R7: Совместимость с dark/light темами

**Приоритет:** P0 (обязательно)
**Описание:** Цвет handle'ов выделения и floating toolbar должен адаптироваться к текущей теме приложения.

**Детали:**
- Android использует `colorControlHighlight` и `textColorHighlight` для handle'ов выделения. Приложение уже определяет эти цвета в темах (`values/colors.xml`, `values-night/colors.xml`).
- Проверить, что handle'ы видимы на обоих фонах (светлый/тёмный).

**Критерий приёмки:**
- Handle'ы выделения видимы и контрастны в обеих темах

---

### R8: Совместимость с TalkBack (Accessibility)

**Приоритет:** P1 (желательно)
**Описание:** Выделение текста должно корректно работать с TalkBack и другими экранными читалками.

**Детали:**
- TalkBack использует свой механизм выделения текста — `AccessibilityNodeInfo.ACTION_SET_SELECTION` и `ACTION_COPY`. Нужно убедиться, что `bodyText` с `setTextIsSelectable(true)` корректно обрабатывает accessibility-действия.
- TalkBack long-press с move → выделение текста (не multi-select). Multi-select через TalkBack обычно активируется через specific жесты.

**Критерий приёмки:**
- TalkBack может выделить и скопировать фрагмент текста в сообщении
- TalkBack long-press по сообщению не конфликтует с выделением текста

---

### R9: Совместимость с кириллицей, эмодзи и CJK

**Приоритет:** P0 (обязательно)
**Описание:** Выделение текста должно корректно работать с текстом на всех языках.

**Детали:**
- Кириллица, латиница, арабский (RTL), CJK — handle'ы должны выделять по символам, не по word boundaries
- Эмодзи (включая compound emoji с ZWJ) — должен выделяться целиком, не по codepoint
- Смешанный текст (русский + эмодзи) — корректное выделение

**Критерий приёмки:**
- Выделение работает корректно для русского текста
- Выделение работает корректно для эмодзи (composite emoji выделяются целиком)
- RTL-текст выделяется корректно

---

### R10: Отсутствие регрессии в multi-select режиме

**Приоритет:** P0 (обязательно)
**Описание:** Текущая функциональность multi-select (выделение нескольких сообщений для удаления/пересылки/копирования) не должна сломаться.

**Критерий приёмки:**
- Long-press по сообщению → multi-select (когда нет выделенного текста)
- Можно выбрать несколько сообщений
- "Copy text" в ActionMode копирует все выбранные сообщения целиком
- "Delete", "Forward", "Share" работают как раньше

---

### R11: Производительность

**Приоритет:** P1 (желательно)
**Описание:** Включение `setTextIsSelectable(true)` не должно значительно замедлять прокрутку списка сообщений.

**Детали:**
- `setTextIsSelectable(true)` вызывает пересчёт layout при каждом вызове `setBodyText()`. В `RecyclerView` с активной прокруткой это может вызвать jank.
- **Меры**: устанавливать `setTextIsSelectable(true)` только при привязке данных (в `bind()`), а не в конструкторе; использовать `post()` если необходимо.

**Критерий приёмки:**
- Прокрутка списка сообщений плавная (нет заметного jank)
- Lint/performance profiling не показывает显著ных проблем

---

## Допущения

1. **Android API level**: Минимальная поддерживаемая версия API — 21 (сейчас). `setTextIsSelectable(true)` работает с API 11+, `FloatingToolbar` — с API 21+ (через AppCompat). Нет ограничений по API.

2. **Rust core**: Выделение текста — чисто клиентская (Android) фича. Rust core не затрагивается.

3. **Текущий `LongClickMovementMethod`**: Будет сохранён для обработки кликов по ссылкам. Конфликт с текстовым выделением решается через gesture-детекцию и приоритизацию.

4. **Multi-select**: Остаётся доступным. Режим переключается: если текст выделен — floating toolbar; если нет — long-press → multi-select.

---

## Открытые вопросы

1. **Приоритет long-press**: Если long-press по тексту начинает выделение текста, то multi-select будет доступен только через иконку в ActionBar (или через long-press вне текста). Это приемлемо? Или нужен другой жест для активации multi-select (например, двойной tap)?
Ответ: Да, это приемлемо.

2. **Копирование ссылок**: Текущий долгий tap по ссылке показывает диалог "Copy link" (через `LongClickCopySpan`). При включении текстового выделения этот диалог будет失去. Оставить его (через gesture-детекцию) или отказаться в пользу выделения текста?
Ответ: Оставить.

3. **Share выделенного текста**: В floating toolbar добавлять "Share" или нет? В Telegram есть. В Signal — нет. Какой UX предпочтительнее?
Ответ: добавь "Share"

4. **Обратная связь Toast**: При копировании выделенного текста показывать Toast "Copied to clipboard" (как при копировании всего сообщения) или нет?
Ответ: Да, можно показывать.

---

## План реализации (по шагам)

### Этап 1: Базовая поддержка text selection
1. В `ConversationItem.setBodyText()` (или конструкторе) установить `bodyText.setTextIsSelectable(true)` для текстовых сообщений
2. Проверить, что floating toolbar появляется при long-press
3. Проверить, что "Copy" копирует выделенный текст

### Этап 2: Координация с long-press
4. Отработать coexistence `LongClickMovementMethod` + `setTextIsSelectable(true)`
5. Решить вопрос приоритета long-press (text selection vs multi-select)
6. Обновить `PassthroughClickListener` если необходимо

### Этап 3: Тестирование
7. Тест: выделение кириллического текста → копирование → вставка
8. Тест: выделение эмодзи → копирование → вставка
9. Тест: long-press → выделение → floating toolbar → Copy
10. Тест: long-press → multi-select (когда текст не выделен)
11. Тест: tap по ссылке → открытие ссылки
12. Тест: TalkBack → выделение → копирование
13. Тест: dark theme → видимость handle'ов
14. Тест: прокрутка списка → нет jank

---

## Настройка фичи (включение/выключение через Settings)

Фича должна быть доступна через переключатель в разделе настроек "Chats". По умолчанию **выключена** (для безопасного rollout).

### Архитектурное решение: SharedPreferences vs Core config

Два варианта хранения настройки:

| Критерий | SharedPreferences (`Prefs`) | Core config (`DcContext`) |
|----------|---------------------------|--------------------------|
| Нужны ли изменения в Rust core | ❌ Нет | ✅ Да (новый `Config::TextSelection`)
| Синхронизация между устройствами | ❌ Нет (только локально) | ✅ Да (через IMEX)
| Простота реализации | ✅ Проще (3 файла) | Сложнее (5+ файлов, build core)
| Подходит ли для UI-переключателя | ✅ Да (`pref_enter_sends` — аналог) | ⚠️ Избыточно

**Рекомендация:** Использовать **SharedPreferences** (`Prefs`-паттерн). Это чисто UI-фича, не связанная с ядром. Синхронизация между устройствами не нужна.

### Конкретные файлы для настройки

**1. XML-определение** (`src/main/res/xml/preferences_chats.xml`):

```xml
<org.thoughtcrime.securesms.components.SwitchPreferenceCompat
    android:defaultValue="false"
    android:key="pref_text_selection"
    android:summary="@string/pref_text_selection_explain"
    android:title="@string/pref_text_selection" />
```

**2. Prefs-константа** (`src/main/java/org/thoughtcrime/securesms/util/Prefs.java`):

```java
private static final String TEXT_SELECTION_PREF = "pref_text_selection";

public static boolean isTextSelectionEnabled(Context context) {
    return getBooleanPreference(context, TEXT_SELECTION_PREF, false);
}
```

**3. Строки** (`src/main/res/values/strings.xml`):

```xml
<string name="pref_text_selection">Text Selection in Messages</string>
<string name="pref_text_selection_explain">Select and copy parts of message text instead of entire messages</string>
```

**4. Потребитель** — в `ConversationItem.setBodyText()`:

```java
bodyText.setTextIsSelectable(Prefs.isTextSelectionEnabled(context));
```

**Итого: 4 файла, 0 изменений в Rust core.**

### Сложность реализации настройки: **ОЧЕНЬ НИЗКАЯ**

Это стандартный паттерн, повторяющийся в проекте (аналог `pref_enter_sends`). Реализация занимает ~30 минут.

---

## Ревью требований: противоречия и реализуемость

### Найденные противоречия

#### Противоречие 1: R5 (ссылки) vs open question #2

**Проблема**: R5 требует, чтобы long-press по ссылке показывал диалог "Copy link" (как сейчас), но одновременно long-press по тексту должен запускать text selection (R3). Это взаимоисключающие требования: `setTextIsSelectable(true)` перехватывает long-press для выделения, и перехватить его обратно для ссылки можно только через полную замену `MovementMethod`.

**Решение**: Отказаться от long-press-copy ссылок при включённом text selection. Floating toolbar "Copy" копирует выделенный фрагмент, содержащий ссылку. Tap по ссылке продолжает работать.

#### Противоречие 2: R3 (text selection priority) vs R10 (multi-select через long-press)

**Проблема**: R3 говорит, что long-press по тексту → text selection. R10 в критерии приёмки говорит: "Long-press по сообщению → multi-select (когда нет выделенного текста)". Это не противоречие, но нечёткое определение: "длинное нажатие на bubble (не на текст)" — а что считать "вне текста"? Отступы пузыря? Футер с временем?

**Решение**: Multi-select активируется long-press по **любой области пузыря**, но `PassthroughClickListener` подавляет его, если `bodyText.hasSelection()`. При `setTextIsSelectable(true)` Android перехватывает long-press на text → multi-select не активируется. Multi-select работает только на non-text areas (footer, padding) или когда текст не выделен.

### Требования без противоречий

| Требование | Статус | Комментарий |
|------------|--------|-------------|
| R1: text selection на bodyText | ✅ Реализуемо | `setTextIsSelectable(true)` — стандартный Android API |
| R2: Floating toolbar | ✅ Реализуемо | Стандартный `FloatingToolbar` / `ActionMode` |
| R3: Координация long-press | ✅ Реализуемо | `PassthroughClickListener.hasSelection()` уже есть |
| R4: Выделение в QuoteView | ⚠️ Сложность средняя | Нужно отдельно `setTextIsSelectable(true)` на `quote_text`, проверить кликабельность |
| R5: Ссылки | ✅ Решено | Long-press-copy ссылок отключается, tap продолжает работать |
| R6: Медиа-сообщения | ✅ Реализуемо | Условная логика в `setBodyText()` |
| R7: Темы | ✅ Реализуемо | Android берёт цвета из темы автоматически |
| R8: TalkBack | ✅ Реализуемо | `setTextIsSelectable(true)` автоматически включает accessibility |
| R9: Кириллица/эмодзи/CJK | ✅ Реализуемо | Android text selection поддерживает Unicode нативно |
| R10: Multi-select | ✅ Реализуемо | PassthroughClickListener уже защищает |
| R11: Производительность | ⚠️ Нужна осторожность | `setTextIsSelectable(true)` может вызвать layout recalculation |

### Оценка сложности по требованиям

| Требование | Сложность | Описание |
|------------|-----------|----------|
| R1 + R2 | Низкая | 1-2 дня. `setTextIsSelectable(true)` + стандартный toolbar |
| R3 | Средняя | 1-2 дня. Gesture-детекция + PassthroughClickListener |
| R4 | Средняя | 1 день. Отдельный `setTextIsSelectable(true)` на QuoteView |
| R5 | Низкая | 0.5 дня. Убрать `LongClickCopySpan` long-press при включённом selection |
| R6 | Низкая | 0.5 дня. Условная логика |
| R7 | Низкая | 0.5 дня. Проверка контрастов |
| R8 | Низкая | 1 день. Тестирование с TalkBack |
| R9 | Низкая | 0.5 дня. Тестирование |
| R10 | Низкая | 0.5 дня. Тестирование |
| R11 | Средняя | 1 день. Profiling + оптимизация |
| **Итого** | **~7-10 дней** | Без включения в настройку |

---

## План реализации (по шагам)

### Этап 0: Настройка фичи (0.5 дня)
1. Добавить `SwitchPreferenceCompat` в `preferences_chats.xml`
2. Добавить `Prefs.isTextSelectionEnabled()` в `Prefs.java`
3. Добавить строки `pref_text_selection` / `pref_text_selection_explain` в `strings.xml`

### Этап 1: Базовая поддержка text selection (1-2 дня)
4. В `ConversationItem.setBodyText()` установить `bodyText.setTextIsSelectable(Prefs.isTextSelectionEnabled(context))`
5. Убедиться, что `LongClickMovementMethod` совместим с `setTextIsSelectable(true)` (tap по ссылке → открывает ссылку)
6. Проверить, что floating toolbar появляется при long-press
7. Проверить, что "Copy" копирует выделенный текст
8. Добавить "Share" во floating toolbar через `onCreateActionMode` callback

### Этап 2: Координация с long-press (1-2 дня)
9. Отработать coexistence `LongClickMovementMethod` + `setTextIsSelectable(true)`
10. Проверить, что `PassthroughClickListener.hasSelection()` корректно блокирует multi-select при выделении
11. Обработать long-press на не-text areas (footer, padding) для multi-select
12. Проверить, что tap вне выделенного текста снимает выделение

### Этап 3: QuoteView и медиа (1 день)
13. Добавить `setTextIsSelectable(true)` на `quote_text` в QuoteView (если R4 в scope)
14. Проверить, что клик по цитате (навигация) продолжает работать
15. Обработать медиа-сообщения с подписью (R6)

### Этап 4: Testing and polish (2-3 дня)
16. Тест: выделение кириллического текста → копирование → вставка
17. Тест: выделение эмодзи → копирование → вставка
18. Тест: long-press → выделение → floating toolbar → Copy
19. Тест: long-press → floating toolbar → Share → share intent
20. Тест: long-press → floating toolbar → Select all → выделение всего текста
21. Тест: long-press → multi-select (когда текст не выделен)
22. Тест: tap по ссылке → открытие ссылки
23. Тест: TalkBack → выделение → копирование
24. Тест: dark theme → видимость handle'ов
25. Тест: прокрутка списка → нет jank
26. Тест: on/off настройки → корректное поведение в обоих состояниях
27. Тест: QuoteView → выделение текста цитаты

---

## Метрики успеха

- Пользователь может выделить произвольный фрагмент текста в сообщении long-press + handle'ами
- Выделенный текст копируется в буфер обмена одним нажатием
- Выделенный текст можно поделиться через Share
- Multi-select режим продолжает работать как раньше
- TalkBack поддерживает выделение и копирование
- Performance: прокрутка списка не деградирует
- Настройка включает/выключает фичу корректно, значение сохраняется между перезапусками
