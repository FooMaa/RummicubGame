# HTTP API · версия возможностей 2

Все игровые маршруты: `POST /api/...`, JSON UTF-8, `Authorization: Bearer <token>`. Health: `GET /health`, ответ `status=ok, apiVersion=2`. В продакшене используйте HTTPS. Успех — 200; ошибки — JSON `{"error":"..."}` с 400/401/404/405/413/429/500. Тело до 64 KiB.

| Маршрут | Тело | Ответ |
|---|---|---|
| `auth/register` | `{name,password}` | `{id,name,token}` |
| `auth/login` | `{name,password}` | `{id,name,token}` |
| `auth/logout` | `{}` | `{ok:true}` |
| `room/create` | `{turnSeconds:60}` (0/60/120) | View |
| `room/join` | `{code}` | View |
| `room/current` | `{}` | View или `{code:""}` |
| `room/state` | `{code}` | View |
| `room/bot` | `{code}` | View; только хозяин, до старта |
| `room/removeBot` | `{code,player}` | View; только хозяин, до старта |
| `room/start` | `{code}` | View; только хозяин |
| `room/rematch` | `{code}` | View лобби; хозяин после завершения |
| `room/play` | `{code,revision,board:[[id,...],...]}` | View |
| `room/draw` | `{code,revision}` | View |
| `room/edit` | `{code,revision,draftVersion,draft:[[id,...],...],editing:true}` | View |
| `room/leave` | `{code}` | `{ok:true}` |

View: `code,host,me,started,players,turnSeconds,revision,deadline,turn,poolCount,finished,winner,message,rack,board,draft,draftVersion`. Deadline — Unix milliseconds, 0 без таймера. Seat: `id,name,bot,opened,count,score`. До старта игровые поля пусты. Один человек может состоять только в одной комнате.

Ревизия растёт после принятого хода/взятия/таймаута. `edit` не повышает ревизию и не раскрывает черновик другим игрокам. После потерянного ответа клиент читает `state` и сверяет revision; повторная команда со старой revision отклоняется.

`draft` допускает незавершённые комбинации, но не дубликаты, чужие фишки или исчезновение фишек стола. Черновик и `draftVersion` возвращаются только владельцу текущего хода. Ревизия партии при сохранении черновика не меняется. `draftVersion` возрастает внутри хода; запоздавшая старая запись не отменяет новый черновик или сброс стола. `room/state` может принимать те же поля черновика вместе с `revision` для восстановления после ошибки сохранения. Сервер принимает черновик только до дедлайна, а таймаут обрабатывает до исполнения просроченных команд. При новом ходе черновик очищается.
