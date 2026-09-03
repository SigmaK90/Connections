import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestore centrale delle richieste lato server.
 * Intercetta gli oggetti Request deserializzati da JSON, esegue le verifiche di autorizzazione,
 * delega la logica a UserManager e GameManager, e restituisce un'istanza di Response.
 */
public class RequestHandler {

    private final UserManager userManager;
    private final GameManager gameManager;

    /**
     * Costruisce il gestore delle richieste iniettando i gestori degli utenti e di gioco.
     */
    public RequestHandler(UserManager userManager, GameManager gameManager) {
        this.userManager = userManager;
        this.gameManager = gameManager;
    }

    /**
     * Smista ed esegue l'operazione contenuta nella richiesta inoltrata dal client.
     * Restituisce un'istanza di Response che rappresenta l'esito dell'operazione.
     */
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
                    session.setUsername(loginUser.trim());

                    if (request.getUdpPort() > 0 && request.getUdpPort() <= 65535) {
                        session.setUdpPort(request.getUdpPort());
                        userManager.registerUdpPort(loginUser.trim(), request.getUdpPort());
                    }

                    // Sezione 2.1 PDF: restituisce automaticamente i dati della partita corrente al login
                    Map<String, Object> gameInfo = gameManager.getGameInfo(null, loginUser.trim());
                    Response response = Response.ok("Login effettuato con successo!");
                    if (gameInfo != null) {
                        response.setData(gameInfo);
                    }
                    return response;
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

                if (!session.getUsername().equals(oldName.trim())) {
                    return Response.unauthorized("Non puoi modificare le credenziali di un altro utente.");
                }

                boolean updated = userManager.updateCredentials(oldName.trim(), oldPsw, newName, newPsw);
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

                // Utilizza direttamente i dati incapsulati in ProposalResult (nessun rischio di NPE)
                Map<String, Object> data = new HashMap<>();
                data.put("correct", result.isCorrect());
                data.put("oneAway", result.isOneAway());
                data.put("mistakes", result.getMistakes());
                data.put("errorsLeft", result.getErrorsLeft());
                data.put("status", result.getStatus().toString());
                data.put("guessedCategories", result.getGuessedCategories());
                data.put("currentScore", result.getScore());

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