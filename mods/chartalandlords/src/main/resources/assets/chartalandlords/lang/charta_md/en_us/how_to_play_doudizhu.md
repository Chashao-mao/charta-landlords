### **Goal:**

Be the first to get rid of all your cards. One player is the landlord, everyone else are farmers
(1 versus 2 in a 3 player game, 1 versus 3 in a 4 player game).

### **Setup:**

**Players:** 3 (54 card deck) or 4 (108 card double deck)

1. Place a card table and 3~4 game chairs facing the table.
2. Sit on one chair yourself. You can play against other players, or leash a mob (villager, cow, ...)
   and right click a chair to make it sit down - seated mobs play automatically.
3. Put the matching deck on the table: **Doudizhu Deck (54 cards)** for 3 players,
   **Doudizhu Double Deck (108 cards)** for 4 players. The player count must match the deck.
4. Right click the table and pick "Doudizhu" from the game list.

### **Bidding / grabbing the landlord:**

**Grab-the-landlord is the default**: every seat decides once, in seat order, whether to grab, and the
base bid is fixed at 1.

- Every grab doubles the table multiplier, and the **last** player who grabbed becomes the landlord.
- The first grab does not double anything (it is just the normal call), so n grabs count as `n - 1` doublings.
- If nobody grabs it is handled exactly like "nobody bid": redeal, and after two rounds the strongest hand
  is forced to be the landlord.
- The landlord takes the bottom cards (3 in a 3 player game, 8 in a 4 player game); they are shown to everyone.

Turn **grab-the-landlord mode off** in the game options and you get classic bidding instead:

- Every seat bids once in turn: 1, 2 or 3 points, or pass. A bid must be higher than the current highest.
- A bid of 3 ends the bidding immediately; otherwise the highest bidder becomes the landlord.
- If nobody bids the deal is repeated (after two failed rounds the strongest hand is forced to be the landlord).

### **Reveal / double:**

After the landlord is decided (turn this off in the game options) everyone has 10 seconds and
**two independent switches** plus a **confirm**:

- **Reveal**: show your hand to everyone (the face-down fans on the table flip over) and double your
  **personal multiplier**. This switch is **one way** - the cards are already in front of everyone, so it
  cannot be undone and the button turns into "Revealed".
- **Double**: keep your hand hidden and double your **personal multiplier**. You can toggle it back and
  forth until you confirm.
- **Both can be on at once -> a personal multiplier of x4.** The switches take effect immediately, so the
  status line shows your current stake before you confirm, and a fully stacked seat shows an `x4` badge.
- **Confirm** ends your decision. Anyone who has not confirmed when the 10 seconds run out is finished with
  whatever their switches say, so one idle player can never stall the table.
- Once someone reveals, the face-down fans on the table flip over, and you can look at any hand at any time
  through the **Hands** panel described below (the chat also gets a "revealed hand" line).

### **The screen:**

- **Seat bar (top):** one box per seat with a block head, the name, a Landlord/Farmer badge, `R`/`D`/`x4`
  personal-stake badges, the remaining card count and the cumulative score. While bidding / grabbing and
  during the reveal phase a small **decision chip** follows the name: `grab` / `pass` in grab mode,
  `N pt` / `no bid` in bidding mode, and nothing for a seat that has not decided yet. The seat that acts now
  gets a golden outline and a `>` in front of its name (during reveal/double the seats that are highlighted are
  the ones that have **not** confirmed yet), and the seat that played last also shows "last <combination>".
- **Status line:** the bottom row of the seat bar. On the left the current situation (bidding / grabbing /
  reveal-double / your turn / someone's turn, who is the landlord, base - bombs - grabs - multiplier, AI level);
  in grab mode it also names the **current landlord candidate** (or says that nobody has grabbed yet).
  On the right live feedback about your selection: `Selection: pair (2 cards, beats it)` - it turns red when
  the selection cannot beat the last play.
- **Card bands (bottom up):** "Hand" (two fanned rows - the upper row only shows the top-left corner of each
  card, the lower row is fully visible), "Play area" (your selection) and "Last play". Every band has a small
  label on its left. The last-play band shows up to 30 cards (3 rows of 10); anything above that is reported as
  "N more not shown" instead of being silently dropped. The **bottom cards no longer take a full row** - they are
  a small three-card preview in the top right corner, which hands that row's height back to the table.
- **Button column (right):** three mutually exclusive sets sharing one baseline - "Bid 1/2/3" and "No bid"
  while bidding ("Grab"/"No grab" in grab mode), "Reveal", "Double" and "Confirm" during the reveal phase
  (the first two are switches that read back as "Revealed" / "Doubled"), and "Play", "Pass", "Hint", "Retract"
  and "Tidy" while playing. Every button has a tooltip.
- **Card tracker:** the panel header lists how many cards of every rank are still unseen. Its title row has two
  badges: `music ON/OFF` toggles the background music (its volume follows the vanilla Music slider), and
  `Hands` opens the panel below.
- **Hands screen:** click the `Hands` badge (or press **V**) to open a page that lays out every seat's hand and
  the bottom cards at once - your own seat and any seat that **revealed** show real faces (hover a card to see
  its name), every other seat shows as many card backs as it really holds (the count is public, the faces are
  not). Each row lists the name, `landlord/farmer`, `open/hidden` and the remaining count on its left. The back
  button in the top left corner, `ESC`, or pressing `V` again returns to the table, and the music keeps playing
  while you look.
- The four Charta buttons (how to play / options / deck / history) live in the top corners; nothing that
  Doudizhu draws covers them.

### **Playing:**

1. Click cards in your hand to move them into the play area; click several cards to build pairs, straights or
   airplanes. Clicks always target the topmost visible card.
2. Click a card in the play area to put it back into your hand; press **Retract** or right click anywhere to
   retract the whole selection at once. Retract is only enabled while something is selected.
3. Press **Play** to play the selection (**Enter**), **Pass** to skip your turn (disabled when you must lead;
   **Space**) and **Hint** to auto-select the smallest combination that beats the last play.
4. An invalid or too-small selection is **kept**, so you can just drop one card and press Play again instead
   of selecting everything from scratch.
5. When everyone else passes, the table is cleared and the last player leads again.
6. The game ends as soon as one player runs out of cards.

### **Hand order:**

- **Press and drag a hand card** to reorder it: the card **follows your cursor** and a golden arrow plus a
  vertical line above/inside the hand band marks where it will be inserted; releasing inserts it there and the
  other cards shift over. Dropping on empty space inserts it at the start of that segment; dropping outside the
  hand counts as a normal click.
- Dragging switches your hand into **manual order** for the rest of the round: playing and selecting only
  remove the cards involved and the hand is **never re-sorted**, so the layout you arranged stays put.
- **Tidy** clears the manual order and sorts the hand by rank again.
- When the landlord takes the bottom cards with a manual order active, the three new cards are **appended to
  the end** (easy to find) - drag them where you want, or just press Tidy.

### **Combinations:**

- Single, pair, triple, triple with a single, triple with a pair.
- Straight: 5+ consecutive single cards (3~A, no 2 or jokers).
- Double straight: 3+ consecutive pairs (3~A).
- Airplane: 2+ consecutive triples, optionally with the same amount of singles or pairs.
- Four with two: four of a kind plus two singles or two pairs.
- Bomb: four or more of the same rank. Rocket: small + big joker (3 players) or four jokers (4 players).

### **Comparison:**

- Same type and size compares the key rank: 3 < 4 < ... < 10 < J < Q < K < A < 2 < small joker < big joker.
- Bombs beat any non-bomb; more cards beat fewer cards, then the rank decides; the rocket is the highest.

### **Scoring (can be turned off in the options):**

- **Base** = the bid (always 1 in grab mode).
- **Table multiplier** (shared by everyone): every bomb/rocket doubles it, every grab beyond the first doubles
  it, and spring (the landlord ran out while the farmers played nothing) and anti-spring (the farmers won while
  the landlord played only one hand) each double it again.
- **Personal multiplier** (only affects its owner): reveal and double each double it, so a player who turns
  both on is at x4 - a player is at x1, x2 or x4.
- Settlement is **pairwise**: the landlord is settled against every farmer separately, and that pair's stake is
  `base x table multiplier x landlord personal multiplier x that farmer's personal multiplier`. The winner of the
  pair takes that stake, the loser pays it - so a 3 player game moves 2 stakes and a 4 player game 3 stakes,
  and the total is always exactly zero.
- The breakdown is printed in chat and the **cumulative score** is shown at the right of each seat box's second
  row (the very first round legitimately shows 0 because nothing has settled yet).

### **Timeouts and table display:**

- Bidding has a 30 second timeout that passes automatically, reveal/double has a 10 second timeout that
  confirms whatever the switches say, and playing has **no** timeout at all. Hotkeys: **Enter** plays,
  **Space** passes and **V** opens the Hands screen.
- After the deal the table lays out every seat's **hand fan** (face down) and the **bottom cards**, and they
  **stay there for the whole round**: every fan stays in its own slot so it never covers the table, which means a
  glance downwards always tells you how many cards everyone holds. The bottom cards are face down while bidding;
  **once the landlord is decided they collapse into a single face-down "landlord card" lying crosswise in front
  of them** - that is the "who is the landlord" marker, while the bottom cards themselves stay readable inside the
  screen (the bottom preview, the Hands panel and the chat announcement).
- The table **never draws any text**: a card is only 0.156 blocks wide, so any floating label is bigger than a
  card and blurs from every angle. The situation is expressed purely through card positions and orientation
  instead - every fan **stays in its own slot on the table** for the whole round, **the seat on turn has its fan
  pushed towards the centre** and the previous seat slides back (client side positions are interpolated, so it
  glides), **the last play leans towards whoever played it and lies facing them** (so different players' plays
  point in different directions; it returns to the middle once everyone passes and the hand is collected), and the
  **landlord has a single face-down card lying crosswise in front of them**. You can read the whole situation
  **without opening the game screen**. A player who **revealed** keeps their hand face up for the whole round.
- The **AI difficulty** game option offers `0 cautious`, `1 balanced` and `2 aggressive`. Difficulty only tunes
  the bid / grab / reveal thresholds, when bombs are spent and how soon the AI starts blocking an opponent who
  is nearly out - it never changes a single combination or comparison rule.
