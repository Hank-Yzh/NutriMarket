import {api} from './api.js';
import {requireUser,node,time} from './dietary-ui.js';
import {$,base,states,link,money,runner} from './procurement-ui.js';
let page=1,hasNext=false;const work=runner(()=>{$('#previous').disabled=page===1;$('#next').disabled=!hasNext;});
async function load(){const d=await api(`${base}/orders?page=${page}`);hasNext=d.items.length===10;$('#page-info').textContent=`Page ${page}`;$('#orders').replaceChildren();for(const o of d.items){const row=node('article');row.className='product-match';row.append(link(`${states[o.status]} · ${money(o.total,o.currency)}`,`/order.html?id=${o.orderId}`),node('p',`${time(o.createdAt)} · ${o.orderId}`));$('#orders').append(row);}if(!d.items.length)$('#orders').append(node('p','No orders on this page. Confirm a basket to place one.'));}
$('#previous').onclick=()=>{page--;work(load);};$('#next').onclick=()=>{page++;work(load);};$('#reload').onclick=()=>work(load);
try{await requireUser();await work(load);}catch(e){$('#message').textContent=e.message;}
