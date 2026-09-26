# Autonomous Potential Violation Eradicator (APVE)

![License](https://img.shields.io/badge/License-GPLv3-blue.svg)
![API](https://img.shields.io/badge/Dependency-PacketEvents-orange.svg)

> **Author / Автор:** blackgeyer | **Version / Версия:** 1.3.5 | **License / Лицензия:** GPLv3  
> 📹 **Plugin Showcase / Видео с демонстрацией:** [Watch on YouTube](https://youtu.be/tICOrnjpwYc?si=7kcUQCJTbV0Q8HvM)

> **Documentation**: https://blackgeyer.github.io/APVE/

---

## 🇬🇧 English

Forget about ads, sharing adult content, insults, and other nonsense in the chat if you're using APVE!

APVE: High-performance, fully asynchronous Minecraft chat moderation plugin. Designed to keep your server chat clean with zero main-thread overhead. Powered by **PacketEvents**, **Aho-Corasick** pattern matching, and **Fuzzy Matching** fallback system.

### 🚀 Key Features & Architecture
* **Two-Stage Pattern Matching ($O(N)$ + Fuzzy Fallback):** Words are not strictly checked by 100% exact equality alone. The plugin first uses lightning-fast Aho-Corasick string matching. If an exact match is not found in the dictionary, it automatically triggers **Fuzzy Matching** to compare the word's similarity against dictionary patterns, catching bypasses, intentional typos, and modified letters.
* **100% Asynchronous Netty Interception:** Intercepts and drops chat packets at the network layer before they reach the server's main thread.
* **Smart Text Normalization:** Strips homoglyphs, obfuscated characters, and leetspeak bypasses automatically.
* **Offline Punishment Pipeline:** Issues warnings and executes configurable punishment commands automatically.

### 🛠 Installation
1. Install **PacketEvents** (Spigot/Paper version, NOT Bungee/Velocity proxy version) into `/plugins/`.
2. Install your primary punishment engine (e.g., **EssentialsX**, **AdvancedBan**, etc.) into `/plugins/`.
3. Place `A.P.V.E.jar` into `/plugins/`.
4. Restart the server.

### ⚙️ Technical Compatibility
* **Cores:** Paper, Purpur, Spigot, etc. (any standard Bukkit-based core).
* **Minecraft Versions:** 1.20.5 – 26.x+  
* **Java:** 21

### ⚠️ Important Usage Notes
* Configure `config.yml` before deploying to production to set up punishment command templates and avoid false positives.
* Due to the internal normalization pipeline, all banned word patterns in `config.yml` must be entered using **Latin characters only** (e.g., use `shlyuha` instead of `шлюха`). Refer to `documentation.txt` for details.

### 💻 Commands & Permissions
| Command | Description | Permission | Default |
| :--- | :--- | :--- | :--- |
| `/apve` | Base command for plugin management. | `apve.use` | OP |
| `/apve reload` | Reloads plugin configuration. | `apve.reload` | OP |
| `/apve warns show {player}` | Displays current warning count for a player. | `apve.warns.show` | OP |
| `/apve warns remove {player} {amount}` | Removes a specified number of warnings from a player. | `apve.warns.remove` | OP |
| `/apve warns clear {player}` | Resets all warnings for a player. | `apve.warns.clear` | OP |
| `/apve notify toggle` | Toggles staff alerts for detected violations. | `apve.notify.toggle` | OP |
| `/apve check {string}` | Normalizes and tests a string against filter rules. | `apve.check` | OP |
| `/apve help` | Displays available commands and usage info. | `apve.help` | OP |

### 🔑 Additional Permissions & Immunity Nodes
| Permission | Description | Default |
| :--- | :--- | :--- |
| `apve.violation.notify` | Allows receiving real-time chat notifications about player violations. | False |
| `apve.insult.immune` | Grants immunity against general insult detections. | False |
| `apve.fam.insult.immune` | Grants immunity against family insult detections. | False |
| `apve.caps.immune` | Grants immunity against upper-case (caps) filter. | False |
| `apve.spam.immune` | Grants immunity against spam filter. | False |
| `apve.adult.content.immune` | Grants immunity against adult content filter. | False |
| `apve.social.immune` | Grants immunity against social media links filter. | False |
| `apve.advertisement.immune` | Grants immunity against advertisement and external resource sharing. | False |
| `apve.staff.insult.immune` | Grants immunity against staff insult detections. | False |

---

## 🇷🇺 Русский

Забудьте об рекламе, взрослом контенте, оскорблениях и прочей ереси в чате если вы используете APVE!

APVE: Высокопроизводительный асинхронный плагин модерации чата. Поддерживает чистоту сервера в автоматическом режиме с нулевой нагрузкой на главный поток сервера. Работает на базе **PacketEvents**, алгоритма **Aho-Corasick** и интеллектуальной системы **Fuzzy Matching**.

### 🚀 Архитектурные преимущества
* **Двухэтапная проверка ($O(N)$ + Fuzzy Matching):** Проверка слов не ограничивается строго 100% точным совпадением. Плагин сначала выполняет ультрабыстрый поиск через алгоритм Ахо-Корасик. Если точное совпадение в словаре не найдено, автоматически включается **Fuzzy Matching** (нечёткое сравнение), которое сопоставляет степень схожести слова с паттернами из словаря. Это позволяет эффективно ловить обходы, опечатки и намеренно искажённые слова.
* **Асинхронный перехват Netty:** Фильтрация и сброс запрещённых пакетов происходят на сетевом уровне до их обработки главным потоком сервера.
* **Встроенный нормализатор:** Автоматически нейтрализует обходы через замену букв (символы-омоглифы, цифры, спецсимволы).
* **Автоматические оффлайн-наказания:** Автоматический учёт предупреждений и исполнение команд блокировки.

### 🛠 Установка
1. Поместите плагин **PacketEvents** (Spigot/Paper версию, НЕ Proxy/Bungee/Velocity) в папку `/plugins/`.
2. Поместите плагин наказаний (например, **EssentialsX**, **AdvancedBan** и т.д.) в папку `/plugins/`.
3. Загрузите файл `A.P.V.E.jar` в папку `/plugins/`.
4. Перезапустите сервер.

### ⚙️ Совместимость
* **Ядра:** Paper, Purpur, Spigot или любое другое ядро, основанное на Bukkit.
* **Версии Minecraft:** от 1.20.5 до 26.x+
* **Java:** 21

### ⚠️ Важные предупреждения
* Перед запуском настройте `config.yml` и шаблоны команд наказаний, чтобы исключить ложные срабатывания.
* Из-за работы нормализатора все паттерны запрещённых слов в `config.yml` вносятся **строго латиницей** (пример: `шлюха` -> `shlyuha`). Подробный разбор — в `documentation.txt`.

### 💻 Команды и права
| Команда | Описание | Право | По умолчанию |
| :--- | :--- | :--- | :--- |
| `/apve` | Базовая команда управления плагином. | `apve.use` | OP |
| `/apve reload` | Перезагружает конфигурацию плагина. | `apve.reload` | OP |
| `/apve warns show {player}` | Показывает количество предупреждений игрока. | `apve.warns.show` | OP |
| `/apve warns remove {player} {число}` | Снимает указанное количество предупреждений. | `apve.warns.remove` | OP |
| `/apve warns clear {player}` | Полностью очищает предупреждения игрока. | `apve.warns.clear` | OP |
| `/apve notify toggle` | Переключает получение уведомлений о нарушениях. | `apve.notify.toggle` | OP |
| `/apve check {строка}` | Проверяет и нормализует строку на предмет нарушений. | `apve.check` | OP |
| `/apve help` | Выводит список команд и их описание. | `apve.help` | OP |

### 🔑 Дополнительные права и узлы иммунитета
| Право | Описание | По умолчанию |
| :--- | :--- | :--- |
| `apve.violation.notify` | Позволяет получать уведомления о нарушениях игроков в чате в реальном времени. | OP |
| `apve.insult.immune` | Иммунитет к наказаниям за обычные оскорбления. | False |
| `apve.fam.insult.immune` | Иммунитет к наказаниям за оскорбление родных. | False |
| `apve.caps.immune` | Иммунитет к фильтру сообщений верхним регистром (капс). | False |
| `apve.spam.immune` | Иммунитет к фильтру спама. | False |
| `apve.adult.content.immune` | Иммунитет к фильтру контента для взрослых (18+). | False |
| `apve.social.immune` | Иммунитет к фильтру распространения соцсетей. | False |
| `apve.advertisement.immune` | Иммунитет к фильтру рекламы и сторонних ресурсов. | False |
| `apve.staff.insult.immune` | Иммунитет к наказаниям за оскорбление администрации (Staff). | False |

---

## 📄 License / Лицензия
Distributed under the **GPLv3 License**. / Распространяется по лицензии **GPLv3**.
