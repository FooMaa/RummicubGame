# HTTP API v1

Все игровые маршруты: `POST /api/...`, JSON UTF-8, `Authorization: Bearer <token>`. Health: `GET /health`. В продакшене используйте HTTPS. Успех — 200; ошибки — JSON `{"error":"..."}` с 400/401/404/405/413/429/500. Тело до 64 KiB.

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
| `room/edit` | `{code,revision,editing:true}` | View |
| `room/leave` | `{code}` | `{ok:true}` |

View: `code,host,me,started,players,turnSeconds,revision,deadline,turn,poolCount,finished,winner,message,rack,board`. Deadline — Unix milliseconds, 0 без таймера. Seat: `id,name,bot,opened,count,score`. До старта игровые поля пусты. Один человек может состоять только в одной комнате.

Ревизия растёт после принятого хода/взятия/таймаута. `edit` не повышает ревизию и не раскрывает черновик другим игрокам. После потерянного ответа клиент читает `state` и сверяет revision; повторная команда со старой revision отклоняется.
