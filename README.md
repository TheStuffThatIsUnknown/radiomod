<div>
    <h1 align="center" style="display: block; margin-bottom: 10px;">Live Radio Mod (forked)
    <div> Check out <a href="https://github.com/sergejsaenko/radiomod">original repository</a>
    </div></h1>
    <p align="center">
        A NeoForge mod for Minecraft 1.21.1 that adds a placeable steampunk-style radio block capable of streaming real internet radio stations directly into your game world.
    </p>
    <h1></h1>
    <div>
        <h2>Features</h2>
        <ul>
            <li><strong>Radio Block</strong> — a decorative block you can place anywhere in your world. Right-click it to open the station selector GUI.</li>
            <li><strong>Live Internet Radio</strong> — streams real radio stations over the internet with positional 3D audio. The closer you are to the block, the louder it plays. MP3 and AAC/AAC+ streams are supported (bundled JLayer + JAAD decoders).</li>
            <li><strong>Country / Region Selection</strong> — choose from a wide range of countries (Ukraine, Germany, USA, UK, France, Japan, South Korea, Austria, Brazil, Russia, Jamaica, Egypt, and more) and pick a station from each region.</li>
            <li><strong>Ukraine built-in</strong> — 27 Ukrainian stations (Radio ROKS, Hit FM, KISS FM, Melodia FM, Relax, Army FM, Ukrainian Radio UR-1, Radio Bayraktar, FM Galychyna, …) imported from <code>live_streams.sii</code>, available offline as fallback and via live fetch (country code UA).</li>
            <li><strong>Custom stations</strong> — add any internet radio in <code>config/radiomod_custom_stations.json</code> (ETS2-style, editable at runtime). Server options in <code>config/radiomod.json</code> (<code>allowCustomStations</code>, <code>customRequireOp</code>, <code>maxStations</code>, <code>fetchLimit</code>).</li>
            <li><strong>Speakers</strong> — link any number of speaker blocks to one radio (right-click the speaker). All speakers play the same station, each with its own position.</li>
            <li><strong>Radio Remote</strong> — opens the nearest radio GUI within 32 blocks. Made for radios riding Create trains/contraptions that can't be clicked directly.</li>
            <li><strong>Dynamic Station Fetching</strong> — on the client, stations are fetched live from a radio browser API (stable votes-based ordering, mirror failover, HLS/unsupported codecs filtered out). Fallback station lists are provided for offline use.</li>
        </ul>
        <h1></h1>
        <h2>Requirements</h2>
        <table>
            <thead>
                <tr><th>Dependency</th><th>Version</th></tr>
            </thead>
            <tbody>
                <tr><td>Minecraft</td><td>1.21.1</td></tr>
                <tr><td>NeoForge</td><td>21.1+</td></tr>
                <tr><td>JEI <em>(optional)</em></td><td>19+</td></tr>
            </tbody>
        </table>
        <h1></h1>
        <h2>Create Aeronautics</h2>
        <p>
            The Radio Mod fully supports <strong>physics-based moving structures</strong>. When placed on a moving vehicle (e.g. an airship built with <a href="https://github.com/Creators-of-Aeronautics/Simulated-Project">Create Aeronautics</a>, the radio block moves along with it. The audio position is continuously updated to follow the block's world position, so the sound stays anchored to the radio wherever it travels.
        </p>
        <h1></h1>
        <h2>Create trains / contraptions</h2>
        <p>
            Radios riding a <strong>Create train or contraption</strong> keep playing while it moves (world position is resolved via the contraption transform with reflection — no hard Create dependency). If the radio can't be clicked while moving, use the <strong>Radio Remote</strong> item: right-click it to open the nearest radio GUI within 32 blocks.
        </p>
        <h1></h1>
    </div>
    <div align="center">
        <a href="https://github.com/ryanhcode/sable">
            <img alt="powered by sable" src="./images/sable_512h.png" width="250">
        </a>
    </div>
</div>

