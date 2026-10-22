# RegionBot – Fabric 1.21.4

## Jak zrobić z tego jeden plik .jar (bez instalowania niczego)
1. Załóż konto na github.com → **New repository** (np. `regionbot`).
2. **Add file → Upload files** → przeciągnij CAŁĄ zawartość tego folderu (razem z folderem `.github`) → **Commit**.
3. Wejdź w zakładkę **Actions** → „Zbuduj mod” zbuduje się sam (ok. 3–5 min).
4. Kliknij gotowe zadanie → na dole **Artifacts → RegionBot-jar** → rozpakuj zip → masz `regionbot-1.0.0.jar`.
5. Wrzuć ten jar do `.minecraft/mods` i odpal profil **Fabric 1.21.4** w launcherze.
   Fabric API jest w środku – nic więcej nie trzeba.

## Sterowanie
- **J** – róg 1, drugi raz **J** – róg 2 → czerwony obszar (patrzysz na blok do 96 kratek, albo bierze blok pod tobą).
- **L** – menu: Start / Stop, zmiana klawiszy Start/Stop, usuwanie obszarów.
- **O / P** – domyślnie Start / Stop (zmienisz w menu L albo w Opcje → Sterowanie → RegionBot).

## Co robi bot
- Leci kolejno do obszarów na elytrze z fajerwerkami (sam zakłada elytrę, sam bierze fajerwerki na pasek).
- Kopie siekierą drewno dębowe, ciemnodębowe i akacjowe bez opóźnienia między blokami.
- Obszar pusty → następny. Wszystkie puste → czeka, aż odrosną.
- Gracz w promieniu 8 kratek → od razu odlatuje do kolejnego obszaru.
