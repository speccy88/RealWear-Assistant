package com.fred.realweargpt;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class ToolCatalog {
    static final String GENERATE_IMAGE = "generate_image";

    private ToolCatalog() { }
    public static JSONArray create(boolean allowImages) {
        try {
            JSONArray tools = new JSONArray();
            tools.put(function("display_text", "Afficher spontanément une information utile et concise à l’écran. Le titre et le contenu doivent être en français, sans slogan. Ne pas répéter cet appel à chaque phrase.",
                props("title", string(), "text", string())));
            tools.put(function("display_table", "Afficher une comparaison. Maximum 3 colonnes et 12 lignes, cellules courtes. Fournir les unités et les sources dans le texte oral.",
                props("title", string(), "columns", new JSONObject().put("type", "array").put("items", string()).put("minItems", 1).put("maxItems", 3),
                    "rows", new JSONObject().put("type", "array").put("maxItems", 12).put("items", new JSONObject().put("type", "array").put("items", string()).put("maxItems", 3)))));
            tools.put(function("display_image", "Afficher une vraie image publique par son URL HTTPS directe issue d’une source vérifiée. Ne jamais inventer l’URL ni utiliser une page HTML. Ne pas confondre image trouvée et image générée.",
                props("title", string(), "url", string(), "caption", string())));
            tools.put(function("capture_photo", "Seulement si l’utilisateur demande de regarder ou photographier ce qui se trouve devant lui : prendre une photo avec la caméra et l’envoyer pour analyse. Jamais de capture périodique implicite.",
                props("question", string())));
            tools.put(function("navigate_screen", "Naviguer dans l’affichage local : page suivante/précédente, historique, agrandir/réduire, masquer/afficher, afficher les sources ou la conversation.",
                props("action", new JSONObject().put("type", "string").put("enum", new JSONArray().put("next").put("previous").put("back").put("larger").put("smaller").put("hide").put("show").put("sources").put("conversation")))));
            tools.put(function("set_mode", "À la demande de l’utilisateur uniquement : revenir à WearHF/terminer la conversation ou couper/remettre le microphone. Le retour à WearHF ferme la connexion OpenAI.",
                props("mode", new JSONObject().put("type", "string").put("enum", new JSONArray().put("wearhf").put("stop").put("mute").put("unmute")))));
            if (allowImages) {
                tools.put(function(GENERATE_IMAGE, "Générer et afficher une nouvelle image avec " + ImageGenerator.MODEL_LABEL
                        + " uniquement si l’utilisateur demande explicitement de créer, dessiner ou illustrer quelque chose. "
                        + "Appeler une seule fois et attendre le résultat avant d’annoncer l’affichage. "
                        + "Le prompt décrit précisément l’image demandée, sans ajouter de données personnelles.",
                        props("prompt", string())));
            }
            return tools;
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static JSONObject string() throws JSONException { return new JSONObject().put("type", "string"); }
    private static JSONObject props(Object... pairs) throws JSONException {
        JSONObject p = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) p.put((String) pairs[i], pairs[i + 1]); return p;
    }
    private static JSONObject function(String name, String description, JSONObject properties) throws JSONException {
        JSONArray required = new JSONArray(); java.util.Iterator<String> keys = properties.keys(); while (keys.hasNext()) required.put(keys.next());
        return new JSONObject().put("type", "function").put("name", name).put("description", description).put("strict", true)
            .put("parameters", new JSONObject().put("type", "object").put("properties", properties).put("required", required).put("additionalProperties", false));
    }
}
