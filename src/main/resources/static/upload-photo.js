// Keep selected meal photos within the API limits while preserving their aspect ratio.
export async function prepareMealPhoto(file) {
    if (!['image/jpeg', 'image/png'].includes(file.type)) throw new Error('Please select a JPEG or PNG photo, or describe your meal instead.');
    if (file.size > 30 * 1024 * 1024) throw new Error('Select a photo smaller than 30MB, or describe your meal instead.');
    let bitmap;
    try { bitmap = await createImageBitmap(file); }
    catch { throw new Error('Unable to read the photo. Select another JPEG or PNG, or describe your meal instead.'); }
    try {
        const {width, height} = bitmap;
        if (Math.min(width, height) < 64) throw new Error('The photo is too small to analyse. Use a larger photo or describe your meal instead.');
        if (Math.min(width, height) >= 224 && Math.max(width, height) <= 2048 && file.size <= 10 * 1024 * 1024) return file;
        const scale = Math.min(224 / Math.min(width, height), 2048 / Math.max(width, height));
        const ratio = Math.min(width, height) < 224 ? scale : Math.min(1, 2048 / Math.max(width, height));
        const drawnWidth = Math.max(1, Math.round(width * ratio)), drawnHeight = Math.max(1, Math.round(height * ratio));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(224, drawnWidth); canvas.height = Math.max(224, drawnHeight);
        const context = canvas.getContext('2d');
        if (!context) throw new Error('Unable to prepare the photo. Try another browser or describe your meal instead.');
        context.fillStyle = '#fff'; context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(bitmap, (canvas.width - drawnWidth) / 2, (canvas.height - drawnHeight) / 2, drawnWidth, drawnHeight);
        const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.85));
        if (!blob || blob.size > 10 * 1024 * 1024) throw new Error('Unable to prepare the photo. Use a smaller file or describe your meal instead.');
        return new File([blob], 'meal.jpg', {type: 'image/jpeg'});
    } finally { bitmap.close(); }
}
