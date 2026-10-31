import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source=(await readFile(new URL('../../main/resources/static/upload-photo.js',import.meta.url),'utf8')).replace('export async function','async function');
async function prepare(width,height,{size=1000,type='image/jpeg',blobSize=1000}={}) {
    const state={closed:false,draw:null,canvas:null};
    const file={size,type};
    class File {constructor(parts,name,options){this.size=parts[0].size;this.name=name;this.type=options.type;}}
    const canvas={getContext:()=>({fillRect(){},drawImage:(...args)=>{state.draw=args.slice(1);}}),toBlob:callback=>callback({size:blobSize})};
    const fn=vm.runInNewContext(source+';prepareMealPhoto',{
        File,createImageBitmap:async()=>({width,height,close(){state.closed=true;}}),
        document:{createElement:tag=>{assert.equal(tag,'canvas');state.canvas=canvas;return canvas;}}
    });
    try {return {result:await fn(file),file,state};}
    finally {if(state.canvas) assert.ok(state.closed);}
}
test('ordinary photos retain their original bytes',async()=>{
    const {result,file,state}=await prepare(800,600);
    assert.equal(result,file);assert.equal(state.closed,true);assert.equal(state.canvas,null);
});
test('48-megapixel phone photos are resized before the 20-megapixel API limit',async()=>{
    const {result,state}=await prepare(8000,6000,{size:12*1024*1024});
    assert.equal(result.type,'image/jpeg');assert.equal(state.canvas.width,2048);assert.equal(state.canvas.height,1536);
    assert.deepEqual(Array.from(state.draw),[0,0,2048,1536]);
});
test('small but usable photos are prepared to satisfy the minimum input dimensions',async()=>{
    const {state}=await prepare(160,120);
    assert.equal(state.canvas.width,299);assert.equal(state.canvas.height,224);
});
test('panoramas retain their aspect ratio with padding instead of failing the minimum side check',async()=>{
    const {state}=await prepare(12000,800);
    assert.equal(state.canvas.width,2048);assert.equal(state.canvas.height,224);
    assert.equal(state.draw[2],2048);assert.equal(state.draw[3],137);
});
test('unusable thumbnails and unsupported or oversized files offer the text alternative',async()=>{
    await assert.rejects(prepare(32,32),/too small.*describe your meal/);
    await assert.rejects(prepare(800,600,{type:'image/gif'}),/JPEG or PNG/);
    await assert.rejects(prepare(800,600,{size:31*1024*1024}),/30MB/);
});
test('failed image encoding never sends an oversized file',async()=>{
    await assert.rejects(prepare(8000,6000,{blobSize:11*1024*1024}),/Unable to prepare/);
});
