import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RequestHandler {

    private final UserManager userManager;
    private final GameManager gameManager;

    public RequestHandler(UserManager userManager, GameManager gameManager) {
        this.userManager = userManager;
        this.gameManager = gameManager;
    }

    public Response handle(Request request, Session session) {
        String operation = request.getOperation();
        
        if (operation == null) {
            return Response.error("Richiesta malformata: operazione mancante.");
        }

        switch (operation) {

            case "register": {
                String regName = request.getName();
                String regPsw = request.getPsw();

                if (regName == null || regPsw == null || regName.trim().isEmpty() || regPsw.trim().isEmpty()) {
                    return Response.error("Parametri 'name' e 'psw' obbligatori per la registrazione.");
                }

                boolean registered = userManager.register(regName.trim(), regPsw.trim());
                if (registered) {
                    return Response.ok("Registrazione completata con successo!");
                } else {
                    return Response.error("Nome utente già registrato o non valido.");
                }
            }

            case "login": {
                String loginUser = request.getUsername();
                String loginPsw = request.getPsw();

                if (loginUser == null || loginPsw == null) {
                    return Response.error("Parametri 'username' e 'psw' obbligatori per il login.");
                }

                boolean loggedIn = userManager.login(loginUser, loginPsw);
                if (loggedIn) {
                    session.setUsername(loginUser);

                    if (request.getUdpPort() > 0 && request.getUdpPort() <= 65535) {
                        session.setUdpPort(request.getUdpPort());
                        userManager.registerUdpPort(loginUser, request.getUdpPort());
                    }

                    return Response.ok("Login effettuato con successo!");
                } else {
                    return Response.unauthorized("Credenziali non valide.");
                }
            }

            case "updateCredentials": {
                if (session.getUsername() == null) {
                    return Response.unauthorized("Devi prima effettuare il login.");
                }

                String oldName = request.getOldName();
                String oldPsw = request.getOldPsw();
                String newName = request.getNewName();
                String newPsw = request.getNewPsw();

                if (oldName == null || oldPsw == null) {
                    return Response.error("Parametri 'oldName' e 'oldPsw' obbligatori per l'aggiornamento.");
                }

                if (!session.getUsername().equals(oldName)) {
                    return Response.unauthorized("Non puoi modificare le credenziali di un altro utente.");
                }

                boolean updated = userManager.updateCredentials(oldName, oldPsw, newName, newPsw);
                if (updated) {
                    if (newName != null && !newName.trim().isEmpty()) {
                        session.setUsername(newName.trim());
                    }
                    return Response.ok("Credenziali aggiornate con successo!");
                } else {
                    return Response.error("Aggiornamento fallito: password errata o nuovo nome utente già in uso.");
                }
            }

            case "logout": {
                if (session.getUsername() == null) {
                    return Response.unauthorized("Utente non loggato.");
                }
                userManager.unregisterUdpPort(session.getUsername());
                session.setUsername(null);
                session.setUdpPort(-1);
                return Response.ok("Logout effettuato con successo.");
            }

            case "submitProposal": {
                if (session.getUsername() == null) {
                    return Response.unauthorized("Devi prima effettuare il login.");
                }

                List<String> userWords = request.getWords();
                if (userWords == null || userWords.size() != 4) {
                    return Response.error("Parametro 'words' invalido: richiesta una lista di esattamente 4 parole.");
                }

                String username = session.getUsername();
                GameManager.ProposalResult result = gameManager.submitProposal(username, userWords);

                if (result.isFormalError()) {
                    return Response.error(result.getMessage());
                }

                PlayerGameState state = gameManager.getOrCreatePlayerState(username);

                Map<String, Object> data = new HashMap<>();
                data.put("correct", result.isCorrect());
                data.put("oneAway", result.isOneAway());
                data.put("mistakes", state.getMistakes());
                data.put("status", state.getStatus().toString());
                data.put("guessedCategories", state.getGuessedCategoryNames());
                data.put("currentScore", state.getScore());

                Response response = Response.ok(result.getMessage());
                response.setData(data);
                return response;
            }

            case "requestGameInfo": {
                if (session.getUsername() == null) {
                    return Response.unauthorized("Devi prima effettuare il login.");
                }

                Integer targetGameId = request.getGameId();
                Map<String, Object> gameInfo = gameManager.getGameInfo(targetGameId, session.getUsername());

                if (gameInfo == null) {
                    return Response.error("Partita non trovata o nessuna partita attiva.");
                }

                Response response = Response.ok("Informazioni partita recuperate con successo.");
                response.setData(gameInfo);
                return response;
            }

            case "requestGameStats": {
                if (session.getUsername() == null) {
                    return Response.unauthorized("Devi prima effettuare il login.");
                }

                Integer targetGameId = request.getGameId();
                Map<String, Object> gameStats = gameManager.getGameStats(targetGameId);

                if (gameStats == null) {
                    return Response.error("Statistiche partita non trovate.");
                }

                Response response = Response.ok("Statistiche partita recuperate con successo.");
                response.setData(gameStats);
                return response;
            }

            case "requestLeaderboard": {
                if (session.getUsername() == null) {
                    return Response.unauthorized("Devi prima effettuare il login.");
                }

                String targetPlayer = request.getPlayerName();
                Integer topK = request.getTopPlayers();

                Map<String, Object> leaderboardData = userManager.getLeaderboard(targetPlayer, topK);

                Response response = Response.ok("Classifica recuperata con successo.");
                response.setData(leaderboardData);
                return response;
            }

            case "requestPlayerStats": {
                if (session.getUsername() == null) {
                    return Response.unauthorized("Devi prima effettuare il login.");
                }

                Map<String, Object> playerStats = userManager.getUserStats(session.getUsername());
                if (playerStats == null) {
                    return Response.error("Statistiche utente non trovate.");
                }

                Response response = Response.ok("Statistiche personali recuperate con successo.");
                response.setData(playerStats);
                return response;
            }

            default:
                return Response.error("Operazione sconosciuta: " + operation);
        }
    }
}