# RearGuard : alerte « véhicule derrière » pour Carpuride (Android)

L'appli lit la caméra arrière de l'écran, détecte les voitures, camions, bus et motos
avec un modèle d'IA embarqué (EfficientDet-Lite0, sans internet) et émet des bips :

- **bips lents** : un véhicule est derrière vous, dans votre voie
- **bips rapides** : le véhicule est très proche ou se rapproche vite

Elle peut tourner **en arrière-plan** pendant que CarPlay / Android Auto est affiché.

## 1. Obtenir l'APK

### Option A : sans rien installer (GitHub, gratuit)
1. Créez un compte sur github.com, puis un nouveau dépôt (« New repository »).
2. Cliquez sur « uploading an existing file » et glissez **tout le contenu** de ce dossier
   (y compris le dossier caché `.github`). Validez avec « Commit changes ».
3. Onglet **Actions** : la compilation « Build APK » démarre toute seule (environ 5 min).
4. Une fois terminée (coche verte), ouvrez-la et téléchargez **RearGuard-apk** en bas de la page.
   Dézippez : vous obtenez `app-release.apk`.

> Si le dossier `.github` n'a pas été envoyé, créez à la main le fichier
> `.github/workflows/build.yml` dans GitHub (« Add file » → « Create new file ») et collez-y son contenu.

### Option B : avec Android Studio sur PC
Ouvrez le dossier dans Android Studio, puis **Build → Build APK(s)**.

## 2. Installer sur le Carpuride
1. Copiez `app-release.apk` sur une clé USB ou une carte SD.
2. Sur l'écran : ouvrez le gestionnaire de fichiers, touchez l'APK et autorisez les
   « sources inconnues » si on vous le demande.
3. Ouvrez **RearGuard** et acceptez la permission caméra.

## 3. Réglage
1. Dans « Caméra à surveiller », choisissez chaque caméra tour à tour puis **Tester avec aperçu**
   jusqu'à voir l'image de la caméra arrière.
2. Garez-vous avec une voiture derrière vous et ajustez la **sensibilité** :
   plus elle est haute, plus l'alerte arrive tôt (véhicule plus loin).
3. « Seulement ma voie » ignore les véhicules sur les côtés de l'image (lignes verticales grises).
4. Touchez **Lancer en arrière-plan** : l'appli disparaît, la surveillance continue
   (une notification « RearGuard » reste affichée, avec un bouton « Arrêter »).

## Limites à connaître
- **Alimentation de la caméra arrière** : si votre caméra est branchée sur les feux de recul,
  elle n'est allumée qu'en marche arrière. Il faut qu'elle soit alimentée en permanence
  (branchement en + après contact) pour une surveillance en roulant.
- **Caméra invisible** : sur certains écrans, la caméra arrière est gérée uniquement par le
  système et n'apparaît pas dans la liste. Dans ce cas l'appli ne peut pas y accéder.
- **Caméra occupée** : si l'appli dashcam d'origine enregistre la caméra arrière en continu,
  Android peut refuser de la partager (message « caméra occupée »).
- Le **son** sort sur le flux multimédia : réglez le volume média de l'écran.
- La nuit, la détection dépend de la qualité de l'image (phares bien visibles = OK).
- C'est une **aide**, pas un système de sécurité homologué : gardez l'œil sur vos rétros.
