# Signature du prototype

`realwear-debug.jks` est une clé de signature de développement distribuée avec le projet. Elle permet de réinstaller des versions compilées localement sans changer la signature de l’APK de test.

- Alias : `androiddebugkey`
- Mot de passe du magasin et de la clé : `android`
- Usage : APK de développement RealWear Assistant uniquement

Cette clé est volontairement partagée avec le code source. Elle n’offre aucune preuve d’identité de l’auteur et ne doit pas être utilisée pour une version de production. Elle ne contient pas la clé API OpenAI.
