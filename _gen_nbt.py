import nbtlib, os
from nbtlib.tag import *

root = Compound()
root['DataVersion'] = Int(3953)
root['size'] = IntArray([5, 3, 5])
root['palette'] = List[Compound]([
    Compound({'Name': String('minecraft:stone_bricks')}),
    Compound({'Name': String('minecraft:air')})
])
blks = List[Compound]()
for y in range(3):
    for z in range(5):
        for x in range(5):
            s = 0 if y == 0 else 1
            blks.append(Compound({'pos': IntArray([x, y, z]), 'state': Int(s)}))
root['blocks'] = blks
root['entities'] = List[Compound]()
data = nbtlib.File(root, gzipped=True)
target = r'd:\Files\Project_MinecraftMods\BallisticsFramework\src\main\resources\data\ballistics_framework\structure\empty_arena.nbt'
os.makedirs(os.path.dirname(target), exist_ok=True)
data.save(target)
print(f'OK: {os.path.getsize(target)} bytes')
